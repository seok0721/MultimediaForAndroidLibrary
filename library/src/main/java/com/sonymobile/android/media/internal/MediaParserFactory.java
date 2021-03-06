/*
 * Copyright (C) 2015 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.sonymobile.android.media.internal;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class MediaParserFactory {

    private static final boolean LOGS_ENABLED = Configuration.DEBUG || false;

    private static final String TAG = "MediaParserFactory";

    public static MediaParser createParser(FileDescriptor fd, Long offset, Long length) {
        Class[] parameterTypes = {
                FileDescriptor.class, Long.TYPE, Long.TYPE
        };

        MediaParser parser = null;
        for (int i = 0; i < registeredParsers.length; i++) {
            Constructor c = null;
            try {
                c = registeredParsers[i].getConstructor(parameterTypes);
            } catch (NoSuchMethodException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Unable to find constructor", e);
                continue;
            }
            try {
                parser = (MediaParser)c.newInstance(fd, offset, length);
                if (parser.canParse()) {
                    if (parser.parse()) {
                        return parser;
                    }
                }
                parser.release();
            } catch (InstantiationException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Unable to instantiate parser class", e);
                if (parser != null) {
                    parser.release();
                }
            } catch (IllegalAccessException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Illegal access to parser class constructor", e);
                if (parser != null) {
                    parser.release();
                }
            } catch (IllegalArgumentException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Illegal argument when creating parser", e);
                if (parser != null) {
                    parser.release();
                }
            } catch (InvocationTargetException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Unable to invoke parser constructor", e);
                if (parser != null) {
                    parser.release();
                }
            }
        }
        return null;
    }

    public static MediaParser createParser(String path,
            Long offset, Long length, int maxBufferSize, Handler notify) throws IOException {

        boolean calledOnUiThread = false;
        Looper myLooper = Looper.myLooper();
        Looper mainLooper = Looper.getMainLooper();

        if (myLooper != null && mainLooper != null && myLooper.equals(mainLooper)) {
            calledOnUiThread = true;
        }

        if (calledOnUiThread) {
            ParameterHolder holder = new ParameterHolder();
            holder.path = path;
            holder.offset = offset;
            holder.length = length;
            holder.maxBufferSize = maxBufferSize;
            holder.notify = notify;

            ParserCreaterTask task = new ParserCreaterTask();
            task.execute(holder);

            try {
                return task.get(30000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (LOGS_ENABLED) Log.e(TAG, "InterruptedException from ParserCreatertask", e);
            } catch (ExecutionException e) {
                if (LOGS_ENABLED) Log.e(TAG, "ExecutionException from ParserCreatertask", e);
            } catch (TimeoutException e) {
                if (LOGS_ENABLED) Log.e(TAG, "TimeoutException from ParserCreatertask", e);
            }

            return null;

        } else {
            return doCreateParser(path, offset, length, maxBufferSize, notify);
        }
    }

    private static MediaParser doCreateParser(String path,
            Long offset, Long length, int maxBufferSize, Handler notify) throws IOException {

        MediaParser selectedParser = null;
        Class[] parameterTypes = {
                DataSource.class
        };
        MediaParser parser = null;
        if (maxBufferSize == -1) {
            maxBufferSize = Configuration.DEFAULT_HTTP_BUFFER_SIZE;
        }

        DataSource dataSource;
        try {
            dataSource = DataSource.create(path, offset, length.intValue(),
                    maxBufferSize, notify, null, false);
        } catch (IllegalArgumentException e) {
            if (LOGS_ENABLED) Log.e(TAG, "Could not create DataSource", e);
            return null;
        }

        for (int i = 0; i < registeredParsers.length; i++) {
            Constructor c = null;
            try {
                c = registeredParsers[i].getConstructor(parameterTypes);
            } catch (NoSuchMethodException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Unable to find constructor", e);
                continue;
            }
            try {
                parser = (MediaParser)c.newInstance(dataSource);
                if (parser.canParse()) {
                    if (parser.parse()) {
                        selectedParser = parser;
                        break;
                    }
                }
            } catch (InstantiationException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Unable to instantiate parser class", e);
            } catch (IllegalAccessException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Illegal access to parser class constructor", e);
            } catch (IllegalArgumentException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Illegal argument when creating parser", e);
            } catch (InvocationTargetException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Unable to invoke parser constructor", e);
            }
            try {
                dataSource.reset();
            } catch (IOException e) {
                dataSource = DataSource.create(path, offset, length.intValue(), maxBufferSize,
                        notify, null, false);
            }
        }
        if (selectedParser == null) {
            try {
                dataSource.close();
            } catch (IOException e) {
                if (LOGS_ENABLED) Log.e(TAG, "Exception closing datasource", e);
            }
        }
        return selectedParser;
    }

    private static final Class[] registeredParsers = {
            PiffParser.class, VUParser.class, ISOBMFFParser.class
    };

    private static class ParserCreaterTask extends
            AsyncTask<ParameterHolder, Void, MediaParser> {

        @Override
        protected MediaParser doInBackground(ParameterHolder... params) {
            ParameterHolder holder = params[0];
            if (holder == null) {
                return null;
            }

            try {
                return doCreateParser(holder.path, holder.offset, holder.length,
                        holder.maxBufferSize, holder.notify);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static class ParameterHolder {
        public String path;

        public long offset;

        public long length;

        public int maxBufferSize;

        public Handler notify;
    }
}
