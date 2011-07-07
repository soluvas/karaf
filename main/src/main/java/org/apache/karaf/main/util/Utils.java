/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.main.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;

public class Utils {

    public static File getKarafHome(Class<?> mainClass, String karafHomeProperty, String karafHomeEnv) throws IOException {
        File rc = null;

        // Use the system property if specified.
        String path = System.getProperty(karafHomeProperty);
        if (path != null) {
            rc = validateDirectoryExists(path, "Invalid " + karafHomeProperty + " system property", false, true);
        }

        if (rc == null) {
            path = System.getenv(karafHomeEnv);
            if (path != null) {
                rc = validateDirectoryExists(path, "Invalid " + karafHomeEnv + " environment variable", false, true);
            }
        }

        // Try to figure it out using the jar file this class was loaded from.
        if (rc == null) {
            // guess the home from the location of the jar
            URL url = mainClass.getClassLoader().getResource(mainClass.getName().replace(".", "/") + ".class");
            if (url != null) {
                try {
                    JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
                    url = jarConnection.getJarFileURL();
                    rc = new File(new URI(url.toString())).getCanonicalFile().getParentFile().getParentFile();
                } catch (Exception ignored) {
                }
            }
        }

        if (rc == null) {
            // Dig into the classpath to guess the location of the jar
            String classpath = System.getProperty("java.class.path");
            int index = classpath.toLowerCase().indexOf("karaf.jar");
            int start = classpath.lastIndexOf(File.pathSeparator, index) + 1;
            if (index >= start) {
                String jarLocation = classpath.substring(start, index);
                rc = new File(jarLocation).getCanonicalFile().getParentFile();
            }
        }
        if (rc == null) {
            throw new IOException("The Karaf install directory could not be determined.  Please set the " + karafHomeProperty + " system property or the " + karafHomeEnv + " environment variable.");
        }

        return rc;
    }

    public static File validateDirectoryExists(String path, String errPrefix, boolean createDirectory, boolean validate) {
        File rc;
        try {
            rc = new File(path).getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException(errPrefix + " '" + path + "' : " + e.getMessage());
        }
        if (!rc.exists() && !createDirectory && validate) {
            throw new IllegalArgumentException(errPrefix + " '" + path + "' : does not exist");
        }
        if (!rc.exists() && createDirectory) {
            try {
                rc.mkdirs();
            } catch (SecurityException se) {
                throw new IllegalArgumentException(errPrefix + " '" + path + "' : " + se.getMessage());
            }
        }
        if (rc.exists() && !rc.isDirectory()) {
            throw new IllegalArgumentException(errPrefix + " '" + path + "' : is not a directory");
        }
        return rc;
    }
    
    public static File getKarafDirectory(String directoryProperty, String directoryEnvironmentVariable, File defaultValue, boolean create, boolean validate) {
        File rc = null;
        
        String path = System.getProperty(directoryProperty);
        if (path != null) {
            rc = validateDirectoryExists(path, "Invalid " + directoryProperty + " system property", create, validate);
        }
        
        if (rc == null) {
            path = System.getenv(directoryEnvironmentVariable);
            if (path != null && validate) {
                rc = validateDirectoryExists(path, "Invalid " + directoryEnvironmentVariable  + " environment variable", create, validate);
            }
        }
        
        if (rc == null) {
            rc = defaultValue;
        }
        
        return rc;
    }

    //-----------------------------------------------------------------------
    /**
     * Recursively delete a directory.
     * @param directory directory to delete
     * @throws IOException in case deletion is unsuccessful
     */
    public static void deleteDirectory(File directory)
        throws IOException {
        if (!directory.exists()) {
            return;
        }

        cleanDirectory(directory);
        if (!directory.delete()) {
            String message =
                "Unable to delete directory " + directory + ".";
            throw new IOException(message);
        }
    }

    /**
     * Clean a directory without deleting it.
     * @param directory directory to clean
     * @throws IOException in case cleaning is unsuccessful
     */
    public static void cleanDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            String message = directory + " does not exist";
            throw new IllegalArgumentException(message);
        }

        if (!directory.isDirectory()) {
            String message = directory + " is not a directory";
            throw new IllegalArgumentException(message);
        }

        File[] files = directory.listFiles();
        if (files == null) {  // null if security restricted
            throw new IOException("Failed to list contents of " + directory);
        }

        IOException exception = null;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            try {
                forceDelete(file);
            } catch (IOException ioe) {
                exception = ioe;
            }
        }

        if (null != exception) {
            throw exception;
        }
    }

    /**
     * <p>
     * Delete a file. If file is a directory, delete it and all sub-directories.
     * </p>
     * <p>
     * The difference between File.delete() and this method are:
     * </p>
     * <ul>
     * <li>A directory to be deleted does not have to be empty.</li>
     * <li>You get exceptions when a file or directory cannot be deleted.
     *      (java.io.File methods returns a boolean)</li>
     * </ul>
     * @param file file or directory to delete.
     * @throws IOException in case deletion is unsuccessful
     */
    public static void forceDelete(File file) throws IOException {
        if (file.isDirectory()) {
            deleteDirectory(file);
        } else {
            if (!file.exists()) {
                throw new FileNotFoundException("File does not exist: " + file);
            }
            if (!file.delete()) {
                String message =
                    "Unable to delete file: " + file;
                throw new IOException(message);
            }
        }
    }

}