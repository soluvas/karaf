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
package org.apache.karaf.main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.karaf.main.util.SubstHelper;
import org.apache.karaf.main.util.Utils;

public class PropertiesLoader {

    /**
     * <p>
     * Loads the configuration properties in the configuration property file
     * associated with the framework installation; these properties
     * are accessible to the framework and to bundles and are intended
     * for configuration purposes. By default, the configuration property
     * file is located in the <tt>conf/</tt> directory of the Felix
     * installation directory and is called "<tt>config.properties</tt>".
     * The installation directory of Felix is assumed to be the parent
     * directory of the <tt>felix.jar</tt> file as found on the system class
     * path property. The precise file from which to load configuration
     * properties can be set by initializing the "<tt>felix.config.properties</tt>"
     * system property to an arbitrary URL.
     * </p>
     *
     * @return A <tt>Properties</tt> instance or <tt>null</tt> if there was an error.
     * @throws Exception if something wrong occurs
     */
    static Properties loadConfigProperties(File karafBase) throws Exception {
        // See if the property URL was specified as a property.
        URL configPropURL;

        try {
            File etcFolder = new File(karafBase, "etc");
            if (!etcFolder.exists()) {
                throw new FileNotFoundException("etc folder not found: " + etcFolder.getAbsolutePath());
            }
            File file = new File(etcFolder, Main.CONFIG_PROPERTIES_FILE_NAME);
            configPropURL = file.toURI().toURL();
        }
        catch (MalformedURLException ex) {
            System.err.print("Main: " + ex);
            return null;
        }


        Properties configProps = loadPropertiesFile(configPropURL, false);

        // Perform variable substitution for system properties.
        for (Enumeration<?> e = configProps.propertyNames(); e.hasMoreElements();) {
            String name = (String) e.nextElement();
            configProps.setProperty(name,
                    SubstHelper.substVars(configProps.getProperty(name), name, null, configProps));
        }

        copySystemProperties(configProps);
        return configProps;
    }
    
    /**
     * <p>
     * Loads the properties in the system property file associated with the
     * framework installation into <tt>System.setProperty()</tt>. These properties
     * are not directly used by the framework in anyway. By default, the system
     * property file is located in the <tt>conf/</tt> directory of the Felix
     * installation directory and is called "<tt>system.properties</tt>". The
     * installation directory of Felix is assumed to be the parent directory of
     * the <tt>felix.jar</tt> file as found on the system class path property.
     * The precise file from which to load system properties can be set by
     * initializing the "<tt>felix.system.properties</tt>" system property to an
     * arbitrary URL.
     * </p>
     *
     * @param karafBase the karaf base folder
     */
    static void loadSystemProperties(File karafBase) {
        // The system properties file is either specified by a system
        // property or it is in the same directory as the Felix JAR file.
        // Try to load it from one of these places.
    
        // See if the property URL was specified as a property.
        URL propURL;
        try {
            File file = new File(new File(karafBase, "etc"), Main.SYSTEM_PROPERTIES_FILE_NAME);
            propURL = file.toURI().toURL();
        }
        catch (MalformedURLException ex) {
            System.err.print("Main: " + ex);
            return;
        }
    
        // Read the properties file.
        Properties props = new Properties();
        InputStream is = null;
        try {
            is = propURL.openConnection().getInputStream();
            props.load(is);
            is.close();
        }
        catch (FileNotFoundException ex) {
            // Ignore file not found.
        }
        catch (Exception ex) {
            System.err.println(
                    "Main: Error loading system properties from " + propURL);
            System.err.println("Main: " + ex);
            try {
                if (is != null) is.close();
            }
            catch (IOException ex2) {
                // Nothing we can do.
            }
            return;
        }
    
        // Perform variable substitution on specified properties.
        for (Enumeration<?> e = props.propertyNames(); e.hasMoreElements();) {
            String name = (String) e.nextElement();
            String value = System.getProperty(name, props.getProperty(name));
            System.setProperty(name, SubstHelper.substVars(value, name, null, props));
        }
    }

    static void copySystemProperties(Properties configProps) {
        for (Enumeration<?> e = System.getProperties().propertyNames();
             e.hasMoreElements();) {
            String key = (String) e.nextElement();
            if (key.startsWith("felix.") ||
                    key.startsWith("karaf.") ||
                    key.startsWith("org.osgi.framework.")) {
                configProps.setProperty(key, System.getProperty(key));
            }
        }
    }
    
    static Properties loadPropertiesFile(URL configPropURL, boolean failIfNotFound) throws Exception {
        // Read the properties file.
        Properties configProps = new Properties();
        InputStream is = null;
        try {
            is = configPropURL.openConnection().getInputStream();
            configProps.load(is);
            is.close();
        } catch (FileNotFoundException ex) {
            if (failIfNotFound) {
                throw ex;
            } else {
                System.err.println("WARN: " + configPropURL + " is not found, so not loaded");
            }
        } catch (Exception ex) {
            System.err.println("Error loading config properties from " + configPropURL);
            System.err.println("Main: " + ex);
            return configProps;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            }
            catch (IOException ex2) {
                // Nothing we can do.
            }
        }
        String includes = configProps.getProperty(Main.INCLUDES_PROPERTY);
        if (includes != null) {
            StringTokenizer st = new StringTokenizer(includes, "\" ", true);
            if (st.countTokens() > 0) {
                String location;
                do {
                    location = Utils.nextLocation(st);
                    if (location != null) {
                        URL url = new URL(configPropURL, location);
                        Properties props = loadPropertiesFile(url, true);
                        configProps.putAll(props);
                    }
                }
                while (location != null);
            }
            configProps.remove(Main.INCLUDES_PROPERTY);
        }
        String optionals = configProps.getProperty(Main.OPTIONALS_PROPERTY);
        if (optionals != null) {
            StringTokenizer st = new StringTokenizer(optionals, "\" ", true);
            if (st.countTokens() > 0) {
                String location;
                do {
                    location = Utils.nextLocation(st);
                    if (location != null) {
                        URL url = new URL(configPropURL, location);
                        Properties props = loadPropertiesFile(url, false);
                        configProps.putAll(props);
                    }
                } while (location != null);
            }
            configProps.remove(Main.OPTIONALS_PROPERTY);
        }
        for (Enumeration<?> e = configProps.propertyNames(); e.hasMoreElements();) {
            Object key = e.nextElement();
            if (key instanceof String) {
                String v = configProps.getProperty((String) key);
                configProps.put(key, v.trim());
            }
        }
        return configProps;
    }
}