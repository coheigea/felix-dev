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
package dm.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.service.packageadmin.PackageAdmin;

import dm.Component;
import dm.DependencyManager;

/**
 * This class parses service descriptors generated by the annotation bnd processor.
 * The descriptors are located under META-INF/dependencymanager directory. Such files are actually 
 * referenced by a specific "DependendencyManager-Component" manifest header.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class DependencyManagerRuntime
{
    private ConcurrentHashMap<Bundle, DependencyManager> m_managers =
            new ConcurrentHashMap<Bundle, DependencyManager>();
    private DescriptorParser m_parser;
    private PackageAdmin m_packageAdmin; // Possibly a NullObject

    /**
     * Our constructor. We'll initialize here our DM component builders.
     */
    public DependencyManagerRuntime()
    {
        // Instantiates our descriptor parser, and register our service builders into it.
        m_parser = new DescriptorParser();
        m_parser.addBuilder(new ComponentBuilder());
        m_parser.addBuilder(new AspectServiceBuilder());
        m_parser.addBuilder(new AdapterServiceBuilder());
        m_parser.addBuilder(new BundleAdapterServiceBuilder());
        m_parser.addBuilder(new FactoryConfigurationAdapterServiceBuilder());
        m_parser.addBuilder(new ResourceAdapterServiceBuilder());
    }
    
    /**
     * Return our Object Composition (the Activator will inject dependencies into it)
     */
    protected Object[] getComposition()
    {
        return new Object[] { this, Log.instance() };
    }

    /**
     * Starts our Service (at this point, we have been injected with our bundle context, as well
     * as with our log service. We'll listen to bundle start/stop events (we implement the 
     * SynchronousBundleListener interface).
     */
    protected void start()
    {
        Log.instance().info("Starting Dependency Manager annotation runtime");
    }

    /**
     * Stops our service. We'll stop all activated DependencyManager services.
     */
    @SuppressWarnings("unchecked")
    protected void stop()
    {
        Log.instance().info("Runtime: stopping services");
        for (DependencyManager dm : m_managers.values())
        {
            List<Component> services = new ArrayList<Component>(dm.getComponents());
            for (Component service : services)
            {
                dm.remove(service);
            }
        }

        m_managers.clear();
    }

    /**
     * Load the DM descriptors from the started bundle. We also check possible fragments 
     * attached to the bundle, which might also contain some DM descriptors. 
     * @param bundle the started bundle which contains a DependencyManager-Component header
     */
    protected void bundleStarted(Bundle bundle)
    {
        Log.instance().info("Scanning started bundle %s", bundle.getSymbolicName());
        List<URL> descriptorURLs = new ArrayList<URL>();
        collectDescriptors(bundle, descriptorURLs);
        Bundle[] fragments = m_packageAdmin.getFragments(bundle);
        if (fragments != null)
        {
            for (Bundle fragment : fragments)
            {
                collectDescriptors(fragment, descriptorURLs);
            }
        }
        for (URL descriptorURL : descriptorURLs)
        {
            loadDescriptor(bundle, descriptorURL);
        }
    }
    
    /**
     * Unregisters all services for a stopping bundle.
     * @param b
     */
    @SuppressWarnings("unchecked")
    protected void bundleStopped(Bundle b)
    {
        Log.instance().info("Runtime: Removing services from stopping bundle: %s", b.getSymbolicName());
        DependencyManager dm = m_managers.remove(b);
        if (dm != null)
        {
            List<Component> services = new ArrayList<Component>(dm.getComponents());
            for (Component service : services)
            {
                Log.instance().info("Runtime: Removing service: %s", service);
                dm.remove(service);
            }
        }
    }

    /**
     * Collect all descriptors found from a given bundle, including its possible attached fragments.
     * @param bundle a started bundle containing some DM descriptors
     * @param out the list of descriptors' URLS found from the started bundle, as well as from possibly
     *        attached fragments.
     */
    private void collectDescriptors(Bundle bundle, List<URL> out) {
        String descriptorPaths = (String) bundle.getHeaders().get("DependencyManager-Component");
        if (descriptorPaths == null)
        {
            return;
        }

        for (String descriptorPath : descriptorPaths.split(","))
        {
            URL descriptorURL = bundle.getEntry(descriptorPath);
            if (descriptorURL == null)
            {
                Log.instance()
                        .error("Runtime: " + "DependencyManager component descriptor not found: %s",
                               descriptorPath);
                continue;
            }
            out.add(descriptorURL);
        }        
    }
    
    /**
     * Load a DependencyManager component descriptor from a given bundle.
     * @param b
     * @param descriptorURL
     */
    private void loadDescriptor(Bundle b, URL descriptorURL)
    {
        Log.instance().debug("Parsing descriptor %s from bundle %s", descriptorURL, b.getSymbolicName());

        BufferedReader in = null;
        try
        {
            in = new BufferedReader(new InputStreamReader(descriptorURL.openStream()));
            DependencyManager dm = m_managers.get(b);
            if (dm == null)
            {
                dm = new DependencyManager(b.getBundleContext());
                m_managers.put(b, dm);
            }

            m_parser.parse(in, b, dm);
        }

        catch (Throwable t)
        {
            Log.instance().error("Runtime: Error while parsing descriptor %s from bundle %s",
                                 t,
                                 descriptorURL,
                                 b.getSymbolicName());
        }

        finally
        {
            if (in != null)
            {
                try
                {
                    in.close();
                }
                catch (IOException ignored)
                {
                }
            }
        }
    }
}