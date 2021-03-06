/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi;

import java.io.File;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.ExtensionMapping;
import org.apache.nifi.nar.NarClassLoaders;
import org.apache.nifi.nar.NarUnpacker;
import org.apache.nifi.util.FileUtils;
import org.apache.nifi.util.NiFiProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class NiFi {

    private static final Logger logger = LoggerFactory.getLogger(NiFi.class);
    private final NiFiServer nifiServer;

    public NiFi(final NiFiProperties properties) throws ClassNotFoundException, IOException, NoSuchMethodException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread t, final Throwable e) {
                logger.error("An Unknown Error Occurred in Thread {}: {}", t, e.toString());
                logger.error("", e);
            }
        });

        // register the shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

            @Override
            public void run() {
                // shutdown the jetty server
                shutdownHook();
            }
        }));

        // delete the web working dir - if the application does not start successfully
        // the web app directories might be in an invalid state. when this happens
        // jetty will not attempt to re-extract the war into the directory. by removing
        // the working directory, we can be assured that it will attempt to extract the
        // war every time the application starts.
        File webWorkingDir = properties.getWebWorkingDirectory();
        FileUtils.deleteFilesInDir(webWorkingDir, null, logger, true, true);
        FileUtils.deleteFile(webWorkingDir, logger, 3);

        detectTimingIssues();

        // redirect JUL log events
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        // expand the nars
        final ExtensionMapping extensionMapping = NarUnpacker.unpackNars(properties);

        // load the extensions classloaders
        NarClassLoaders.load(properties);

        // load the framework classloader
        final ClassLoader frameworkClassLoader = NarClassLoaders.getFrameworkClassLoader();
        if (frameworkClassLoader == null) {
            throw new IllegalStateException("Unable to find the framework NAR ClassLoader.");
        }

        // discover the extensions
        ExtensionManager.discoverExtensions();
        ExtensionManager.logClassLoaderMapping();

        // load the server from the framework classloader
        Thread.currentThread().setContextClassLoader(frameworkClassLoader);
        Class<?> jettyServer = Class.forName("org.apache.nifi.web.server.JettyServer", true, frameworkClassLoader);
        Constructor<?> jettyConstructor = jettyServer.getConstructor(NiFiProperties.class);

        final long startTime = System.nanoTime();
        nifiServer = (NiFiServer) jettyConstructor.newInstance(properties);
        nifiServer.setExtensionMapping(extensionMapping);
        nifiServer.start();
        final long endTime = System.nanoTime();
        logger.info("Controller initialization took " + (endTime - startTime) + " nanoseconds.");
    }

    protected void shutdownHook() {
        try {
            logger.info("Initiating shutdown of Jetty web server...");
            if (nifiServer != null) {
                nifiServer.stop();
            }
            logger.info("Jetty web server shutdown completed (nicely or otherwise).");
        } catch (final Throwable t) {
            logger.warn("Problem occured ensuring Jetty web server was properly terminated due to " + t);
        }
    }

    /**
     * Determine if the machine we're running on has timing issues.
     */
    private void detectTimingIssues() {
        final int minRequiredOccurrences = 25;
        final int maxOccurrencesOutOfRange = 15;
        final AtomicLong lastTriggerMillis = new AtomicLong(System.currentTimeMillis());
        final ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
        final AtomicInteger occurrencesOutOfRange = new AtomicInteger(0);
        final AtomicInteger occurences = new AtomicInteger(0);
        final Runnable command = new Runnable() {
            @Override
            public void run() {
                final long curMillis = System.currentTimeMillis();
                final long difference = curMillis - lastTriggerMillis.get();
                final long millisOff = Math.abs(difference - 2000L);
                occurences.incrementAndGet();
                if (millisOff > 500L) {
                    occurrencesOutOfRange.incrementAndGet();
                }
                lastTriggerMillis.set(curMillis);
            }
        };

        final ScheduledFuture<?> future = service.scheduleWithFixedDelay(command, 2000L, 2000L, TimeUnit.MILLISECONDS);

        final TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                future.cancel(true);
                service.shutdownNow();

                if (occurences.get() < minRequiredOccurrences || occurrencesOutOfRange.get() > maxOccurrencesOutOfRange) {
                    logger.warn("NiFi has detected that this box is not responding within the expected timing interval, which may cause Processors to be scheduled erratically. Please see the NiFi documentation for more information.");
                }
            }
        };
        final Timer timer = new Timer(true);
        timer.schedule(timerTask, 60000L);
    }

    /**
     * Main entry point of the application.
     *
     * @param args
     */
    public static void main(String[] args) {
        logger.info("Launching NiFi...");
        try {
            new NiFi(NiFiProperties.getInstance());
        } catch (final Throwable t) {
            logger.error("Failure to launch NiFi due to " + t, t);
        }
    }
}
