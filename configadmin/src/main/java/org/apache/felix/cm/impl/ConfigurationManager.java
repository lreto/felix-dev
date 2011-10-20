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
package org.apache.felix.cm.impl;


import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

import org.apache.felix.cm.PersistenceManager;
import org.apache.felix.cm.file.FilePersistenceManager;
import org.osgi.framework.*;
import org.osgi.service.cm.*;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;


/**
 * The <code>ConfigurationManager</code> is the central class in this
 * implementation of the Configuration Admin Service Specification. As such it
 * has the following tasks:
 * <ul>
 * <li>It is a <code>BundleActivator</code> which is called when the bundle
 * is started and stopped.
 * <li>It is a <code>BundleListener</code> which gets informed when the
 * states of bundles change. Mostly this is needed to unbind any bound
 * configuration in case a bundle is uninstalled.
 * <li>It is a <code>ServiceListener</code> which gets informed when
 * <code>ManagedService</code> and <code>ManagedServiceFactory</code>
 * services are registered and unregistered. This is used to provide
 * configuration to these services. As a service listener it also listens for
 * {@link PersistenceManager} instances being registered to support different
 * configuration persistence layers.
 * <li>A {@link ConfigurationAdminFactory} instance is registered as the
 * <code>ConfigurationAdmin</code> service.
 * <li>A {@link FilePersistenceManager} instance is registered as a default
 * {@link PersistenceManager}.
 * <li>Last but not least this instance manages all tasks laid out in the
 * specification such as maintaining configuration, taking care of configuration
 * events, etc.
 * </ul>
 * <p>
 * The default {@link FilePersistenceManager} is configured with a configuration
 * location taken from the <code>felix.cm.dir</code> framework property. If
 * this property is not set the <code>config</code> directory in the current
 * working directory as specified in the <code>user.dir</code> system property
 * is used.
 */
public class ConfigurationManager implements BundleActivator, BundleListener
{

    /**
     * The name of the bundle context property defining the location for the
     * configuration files (value is "felix.cm.dir").
     *
     * @see #start(BundleContext)
     */
    public static final String CM_CONFIG_DIR = "felix.cm.dir";

    /**
     * The name of the bundle context property defining the maximum log level
     * (value is "felix.cm.loglevel"). The log level setting is only used if
     * there is no OSGi LogService available. Otherwise this setting is ignored.
     * <p>
     * This value of this property is expected to be an integer number
     * corresponding to the log level values of the OSGi LogService. That is 1
     * for errors, 2 for warnings, 3 for informational messages and 4 for debug
     * messages. The default value is 2, such that only warnings and errors are
     * logged in the absence of a LogService.
     */
    public static final String CM_LOG_LEVEL = "felix.cm.loglevel";

    // The name of the LogService (not using the class, which might be missing)
    private static final String LOG_SERVICE_NAME = "org.osgi.service.log.LogService";

    private static final int CM_LOG_LEVEL_DEFAULT = 2;

    // random number generator to create configuration PIDs for factory
    // configurations
    private static Random numberGenerator;

    // the BundleContext of the Configuration Admin Service bundle
    private BundleContext bundleContext;

    // the service registration of the configuration admin
    private volatile ServiceRegistration configurationAdminRegistration;

    // the ServiceTracker to emit log services (see log(int, String, Throwable))
    private ServiceTracker logTracker;

    // the ConfigurationEvent listeners
    private ServiceTracker configurationListenerTracker;

    // service tracker for managed services
    private ServiceTracker managedServiceTracker;

    // service tracker for managed service factories
    private ServiceTracker managedServiceFactoryTracker;

    // PersistenceManager services
    private ServiceTracker persistenceManagerTracker;

    // the thread used to schedule tasks required to run asynchronously
    private UpdateThread updateThread;

    // the thread used to schedule events to be dispatched asynchronously
    private UpdateThread eventThread;

    /**
     * The actual list of {@link PersistenceManager persistence managers} to use
     * when looking for configuration data. This list is built from the
     * {@link #persistenceManagerMap}, which is ordered according to the
     * {@link RankingComparator}.
     */
    private PersistenceManager[] persistenceManagers;

    // the persistenceManagerTracker.getTrackingCount when the
    // persistenceManagers were last got
    private int pmtCount;

    // the cache of Factory instances mapped by their factory PID
    private final Map factories = new HashMap();

    // the cache of Configuration instances mapped by their PID
    // have this always set to prevent NPE on bundle shutdown
    private final Map configurations = new HashMap();

    /**
     * The map of dynamic configuration bindings. This maps the
     * PID of the dynamically bound configuration or factory to its bundle
     * location.
     * <p>
     * On bundle startup this map is loaded from persistence and validated
     * against the locations of installed bundles: Entries pointing to bundle
     * locations not currently installed are removed.
     * <p>
     * The map is written to persistence on each change.
     */
    private DynamicBindings dynamicBindings;

    // the maximum log level when no LogService is available
    private int logLevel = CM_LOG_LEVEL_DEFAULT;

    // flag indicating whether BundleChange events should be consumed (FELIX-979)
    private volatile boolean handleBundleEvents;

    public void start( BundleContext bundleContext )
    {
        // track the log service using a ServiceTracker
        logTracker = new ServiceTracker( bundleContext, LOG_SERVICE_NAME , null );
        logTracker.open();

        // assign the log level
        String logLevelProp = bundleContext.getProperty( CM_LOG_LEVEL );
        if ( logLevelProp == null )
        {
            logLevel = CM_LOG_LEVEL_DEFAULT;
        }
        else
        {
            try
            {
                logLevel = Integer.parseInt( logLevelProp );
            }
            catch ( NumberFormatException nfe )
            {
                logLevel = CM_LOG_LEVEL_DEFAULT;
            }
        }

        // set up some fields
        this.bundleContext = bundleContext;

        // configurationlistener support
        configurationListenerTracker = new ServiceTracker( bundleContext, ConfigurationListener.class.getName(), null );
        configurationListenerTracker.open();

        // initialize the asynchonous updater thread
        ThreadGroup tg = new ThreadGroup( "Configuration Admin Service" );
        tg.setDaemon( true );
        this.updateThread = new UpdateThread( this, tg, "CM Configuration Updater" );
        this.eventThread = new UpdateThread( this, tg, "CM Event Dispatcher" );

        // set up the location (might throw IllegalArgumentException)
        try
        {
            FilePersistenceManager fpm = new FilePersistenceManager( bundleContext, bundleContext
                .getProperty( CM_CONFIG_DIR ) );
            Hashtable props = new Hashtable();
            props.put( Constants.SERVICE_PID, fpm.getClass().getName() );
            props.put( Constants.SERVICE_DESCRIPTION, "Platform Filesystem Persistence Manager" );
            props.put( Constants.SERVICE_VENDOR, "Apache Software Foundation" );
            props.put( Constants.SERVICE_RANKING, new Integer( Integer.MIN_VALUE ) );
            bundleContext.registerService( PersistenceManager.class.getName(), fpm, props );

            // setup dynamic configuration bindings
            dynamicBindings = new DynamicBindings( bundleContext, fpm );
        }
        catch ( IOException ioe )
        {
            log( LogService.LOG_ERROR, "Failure setting up dynamic configuration bindings", ioe );
        }
        catch ( IllegalArgumentException iae )
        {
            log( LogService.LOG_ERROR, "Cannot create the FilePersistenceManager", iae );
        }

        // register as bundle and service listener
        handleBundleEvents = true;
        bundleContext.addBundleListener( this );

        // get all persistence managers to begin with
        pmtCount = 1; // make sure to get the persistence managers at least once
        persistenceManagerTracker = new ServiceTracker( bundleContext, PersistenceManager.class.getName(), null );
        persistenceManagerTracker.open();

        // create and register configuration admin - start after PM tracker ...
        ConfigurationAdminFactory caf = new ConfigurationAdminFactory( this );
        Hashtable props = new Hashtable();
        props.put( Constants.SERVICE_PID, "org.apache.felix.cm.ConfigurationAdmin" );
        props.put( Constants.SERVICE_DESCRIPTION, "Configuration Admin Service Specification 1.2 Implementation" );
        props.put( Constants.SERVICE_VENDOR, "Apache Software Foundation" );
        configurationAdminRegistration = bundleContext.registerService( ConfigurationAdmin.class.getName(), caf, props );

        // start processing the event queues only after registering the service
        // see FELIX-2813 for details
        this.updateThread.start();
        this.eventThread.start();

        // start handling ManagedService[Factory] services
        managedServiceTracker = new ManagedServiceTracker(this);
        managedServiceFactoryTracker = new ManagedServiceFactoryTracker(this);
    }


    public void stop( BundleContext bundleContext )
    {

        // stop handling bundle events immediately
        handleBundleEvents = false;

        // stop queue processing before unregistering the service
        // see FELIX-2813 for details
        if ( updateThread != null )
        {
            updateThread.terminate();
        }
        if ( eventThread != null )
        {
            eventThread.terminate();
        }

        // immediately unregister the Configuration Admin before cleaning up
        // clearing the field before actually unregistering the service
        // prevents IllegalStateException in getServiceReference() if
        // the field is not null but the service already unregistered
        if (configurationAdminRegistration != null) {
            ServiceRegistration reg = configurationAdminRegistration;
            configurationAdminRegistration = null;
            reg.unregister();
        }

        // stop handling ManagedService[Factory] services
        managedServiceFactoryTracker.close();
        managedServiceTracker.close();

        // don't care for PersistenceManagers any more
        persistenceManagerTracker.close();

        // stop listening for events
        bundleContext.removeBundleListener( this );

        if ( configurationListenerTracker != null )
        {
            configurationListenerTracker.close();
        }

        if ( logTracker != null )
        {
            logTracker.close();
        }

        // just ensure the configuration cache is empty
        synchronized ( configurations )
        {
            configurations.clear();
        }

        // just ensure the factory cache is empty
        synchronized ( factories )
        {
            factories.clear();
        }

        this.bundleContext = null;
    }


    // ---------- Configuration caching support --------------------------------

    ConfigurationImpl getCachedConfiguration( String pid )
    {
        synchronized ( configurations )
        {
            return ( ConfigurationImpl ) configurations.get( pid );
        }
    }


    ConfigurationImpl[] getCachedConfigurations()
    {
        synchronized ( configurations )
        {
            return ( ConfigurationImpl[] ) configurations.values().toArray(
                new ConfigurationImpl[configurations.size()] );
        }
    }


    ConfigurationImpl cacheConfiguration( ConfigurationImpl configuration )
    {
        synchronized ( configurations )
        {
            Object existing = configurations.get( configuration.getPid() );
            if ( existing != null )
            {
                return ( ConfigurationImpl ) existing;
            }

            configurations.put( configuration.getPid(), configuration );
            return configuration;
        }
    }


    void removeConfiguration( ConfigurationImpl configuration )
    {
        synchronized ( configurations )
        {
            configurations.remove( configuration.getPid() );
        }
    }


    Factory getCachedFactory( String factoryPid )
    {
        synchronized ( factories )
        {
            return ( Factory ) factories.get( factoryPid );
        }
    }


    Factory[] getCachedFactories()
    {
        synchronized ( factories )
        {
            return ( Factory[] ) factories.values().toArray( new Factory[factories.size()] );
        }
    }


    void cacheFactory( Factory factory )
    {
        synchronized ( factories )
        {
            factories.put( factory.getFactoryPid(), factory );
        }
    }


    // ---------- ConfigurationAdminImpl support

    void setDynamicBundleLocation( final String pid, final String location )
    {
        if ( dynamicBindings != null )
        {
            try
            {
                dynamicBindings.putLocation( pid, location );
            }
            catch ( IOException ioe )
            {
                log( LogService.LOG_ERROR, "Failed storing dynamic configuration binding for " + pid + " to "
                    + location, ioe );
            }
        }
    }


    String getDynamicBundleLocation( final String pid )
    {
        if ( dynamicBindings != null )
        {
            return dynamicBindings.getLocation( pid );
        }

        return null;
    }


    ConfigurationImpl createFactoryConfiguration( ConfigurationAdminImpl configurationAdmin, String factoryPid )
        throws IOException
    {
        return createFactoryConfiguration( factoryPid, configurationAdmin.getBundle().getLocation() );
    }


    ConfigurationImpl createFactoryConfiguration( String factoryPid, String location ) throws IOException
    {
        return createConfiguration( createPid( factoryPid ), factoryPid, location );
    }


    ConfigurationImpl getExistingConfiguration( String pid ) throws IOException
    {
        ConfigurationImpl config = getCachedConfiguration( pid );
        if ( config != null )
        {
            return config;
        }

        PersistenceManager[] pmList = getPersistenceManagers();
        for ( int i = 0; i < pmList.length; i++ )
        {
            if ( pmList[i].exists( pid ) )
            {
                Dictionary props = pmList[i].load( pid );
                config = new ConfigurationImpl( this, pmList[i], props );
                return cacheConfiguration( config );
            }
        }

        // neither the cache nor any persistence manager has configuration
        return null;
    }


    ConfigurationImpl getConfiguration( String pid, String bundleLocation ) throws IOException
    {
        // check for existing (cached or persistent) configuration
        ConfigurationImpl config = getExistingConfiguration( pid );
        if ( config != null )
        {
            return config;
        }

        // else create new configuration also setting the bundle location
        // and cache the new configuration
        config = createConfiguration( pid, null, bundleLocation );
        return cacheConfiguration( config );
    }


    ConfigurationImpl[] listConfigurations( ConfigurationAdminImpl configurationAdmin, String filterString )
        throws IOException, InvalidSyntaxException
    {
        Filter filter = null;
        if ( filterString != null )
        {
            filter = bundleContext.createFilter( filterString );
        }

        List configList = new ArrayList();

        PersistenceManager[] pmList = getPersistenceManagers();
        for ( int i = 0; i < pmList.length; i++ )
        {
            Enumeration configs = pmList[i].getDictionaries();
            while ( configs.hasMoreElements() )
            {
                Dictionary config = ( Dictionary ) configs.nextElement();

                // ignore non-Configuration dictionaries
                String pid = ( String ) config.get( Constants.SERVICE_PID );
                if ( pid == null )
                {
                    continue;
                }

                // CM 1.4 / 104.13.2.3 Permission required
                if ( !configurationAdmin.hasPermission( ( String ) config
                    .get( ConfigurationAdmin.SERVICE_BUNDLELOCATION ) ) )
                {
                    continue;
                }

                // check filter
                if ( filter == null || filter.match( config ) )
                {
                    // ensure the service.pid and returned a cached config if available
                    ConfigurationImpl cfg = getCachedConfiguration( pid );
                    if ( cfg == null )
                    {
                        cfg = new ConfigurationImpl( this, pmList[i], config );
                    }

                    // FELIX-611: Ignore configuration objects without props
                    if ( !cfg.isNew() )
                    {
                        configList.add( cfg );
                    }
                }
            }
        }

        return ( ConfigurationImpl[] ) configList.toArray( new ConfigurationImpl[configList
            .size()] );
    }


    void deleted( ConfigurationImpl config )
    {
        // remove the configuration from the cache
        removeConfiguration( config );
        fireConfigurationEvent( ConfigurationEvent.CM_DELETED, config.getPid(), config.getFactoryPid() );
        updateThread.schedule( new DeleteConfiguration( config ) );
        if ( isLogEnabled( LogService.LOG_DEBUG ) )
        {
            log( LogService.LOG_DEBUG, "DeleteConfiguration(" + config.getPid() + ") scheduled", null );
        }
    }


    void updated( ConfigurationImpl config, boolean fireEvent )
    {
        if ( fireEvent )
        {
            fireConfigurationEvent( ConfigurationEvent.CM_UPDATED, config.getPid(), config.getFactoryPid() );
        }
        updateThread.schedule( new UpdateConfiguration( config ) );
        if ( isLogEnabled( LogService.LOG_DEBUG ) )
        {
            log( LogService.LOG_DEBUG, "UpdateConfiguration(" + config.getPid() + ") scheduled", null );
        }
    }


    void locationChanged( ConfigurationImpl config, String oldLocation )
    {
        fireConfigurationEvent( ConfigurationEvent.CM_LOCATION_CHANGED, config.getPid(), config.getFactoryPid() );
        if ( oldLocation != null )
        {
            updateThread.schedule( new LocationChanged( config, oldLocation ) );
        }
    }


    void fireConfigurationEvent( int type, String pid, String factoryPid )
    {
        FireConfigurationEvent event = new FireConfigurationEvent( type, pid, factoryPid );
        if ( event.hasConfigurationEventListeners() )
        {
            eventThread.schedule( event );
        }
        else if ( isLogEnabled( LogService.LOG_DEBUG ) )
        {
            log( LogService.LOG_DEBUG, "No ConfigurationListeners to send " + event.getTypeName() + " event to.", null );
        }
    }


    // ---------- BundleListener -----------------------------------------------

    public void bundleChanged( BundleEvent event )
    {
        if ( event.getType() == BundleEvent.UNINSTALLED && handleBundleEvents )
        {
            final String location = event.getBundle().getLocation();

            // we only reset dynamic bindings, which are only present in
            // cached configurations, hence only consider cached configs here
            final ConfigurationImpl[] configs = getCachedConfigurations();
            for ( int i = 0; i < configs.length; i++ )
            {
                final ConfigurationImpl cfg = configs[i];
                if ( location.equals( cfg.getDynamicBundleLocation() ) )
                {
                    cfg.setDynamicBundleLocation( null, true );
                }
            }
        }
    }


    // ---------- internal -----------------------------------------------------

    private PersistenceManager[] getPersistenceManagers()
    {
        int currentPmtCount = persistenceManagerTracker.getTrackingCount();
        if ( persistenceManagers == null || currentPmtCount > pmtCount )
        {

            List pmList = new ArrayList();
            PersistenceManager[] pm;

            ServiceReference[] refs = persistenceManagerTracker.getServiceReferences();
            if ( refs == null || refs.length == 0 )
            {
                pm = new PersistenceManager[0];
            }
            else
            {
                // sort the references according to the cmRanking property
                if ( refs.length > 1 )
                {
                    Arrays.sort( refs, RankingComparator.SRV_RANKING );
                }

                // create the service array from the sorted set of referenecs
                for ( int i = 0; i < refs.length; i++ )
                {
                    Object service = persistenceManagerTracker.getService( refs[i] );
                    if ( service != null )
                    {
                        pmList.add( new CachingPersistenceManagerProxy( ( PersistenceManager ) service ) );
                    }
                }

                pm = ( PersistenceManager[] ) pmList.toArray( new PersistenceManager[pmList.size()] );
            }

            pmtCount = pm.length;
            persistenceManagers = pm;
        }

        return persistenceManagers;
    }


    private ServiceReference getServiceReference()
    {
        ServiceRegistration reg = configurationAdminRegistration;
        return ( reg != null ) ? reg.getReference() : null;
    }


    private void configure( ServiceReference sr, ManagedService service )
    {
        String[] pids = getServicePid( sr );
        if ( pids != null )
        {
            for ( int i = 0; i < pids.length; i++ )
            {
                ManagedServiceUpdate update = new ManagedServiceUpdate( pids[i], sr, service );
                updateThread.schedule( update );
                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG, "ManagedServiceUpdate(" + pids[i] + ") scheduled", null );
                }
            }
        }
    }


    private void configure( ServiceReference sr, ManagedServiceFactory service )
    {
        String[] pids = getServicePid( sr );
        if ( pids != null )
        {
            for ( int i = 0; i < pids.length; i++ )
            {
                ManagedServiceFactoryUpdate update = new ManagedServiceFactoryUpdate( pids[i], sr, service );
                updateThread.schedule( update );
                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG, "ManagedServiceFactoryUpdate(" + pids[i] + ") scheduled", null );
                }
            }
        }
    }


    /**
     * Factory method to create a new configuration object. The configuration
     * object returned is not stored in configuration cache and only persisted
     * if the <code>factoryPid</code> parameter is <code>null</code>.
     *
     * @param pid
     *            The PID of the new configuration object. Must not be
     *            <code>null</code>.
     * @param factoryPid
     *            The factory PID of the new configuration. Not
     *            <code>null</code> if the new configuration object belongs to a
     *            factory. The configuration object will not be persisted if
     *            this parameter is not <code>null</code>.
     * @param bundleLocation
     *            The bundle location of the bundle to which the configuration
     *            belongs or <code>null</code> if the configuration is not bound
     *            yet.
     * @return The new configuration object
     * @throws IOException
     *             May be thrown if an error occurrs persisting the new
     *             configuration object.
     */
    ConfigurationImpl createConfiguration( String pid, String factoryPid, String bundleLocation ) throws IOException
    {
        return new ConfigurationImpl( this, getPersistenceManagers()[0], pid, factoryPid, bundleLocation );
    }


    Factory getFactory( String factoryPid ) throws IOException
    {
        Factory factory = getCachedFactory( factoryPid );
        if ( factory != null )
        {
            return factory;
        }

        PersistenceManager[] pmList = getPersistenceManagers();
        for ( int i = 0; i < pmList.length; i++ )
        {
            if ( Factory.exists( pmList[i], factoryPid ) )
            {
                factory = Factory.load( this, pmList[i], factoryPid );
                cacheFactory( factory );
                return factory;
            }
        }

        // if getting here, there is no configuration yet, optionally create new
        return createFactory( factoryPid );
    }


    Factory createFactory( String factoryPid )
    {
        Factory factory = new Factory( this, getPersistenceManagers()[0], factoryPid );
        cacheFactory( factory );
        return factory;
    }


    /**
     * Calls the registered configuration plugins on the given configuration
     * properties from the given configuration object unless the configuration
     * has just been created and not been updated yet.
     *
     * @param props The configuraiton properties run through the registered
     *          ConfigurationPlugin services. This may be <code>null</code>
     *          in which case this method just immediately returns.
     * @param targetPid The identification of the configuration update used to
     *          select the plugins according to their cm.target service
     *          property
     * @param sr The service reference of the managed service (factory) which
     *          is to be updated with configuration
     * @param cfg The configuration object whose properties have to be passed
     *          through the plugins
     */
    private void callPlugins( final Dictionary props, final String targetPid, final ServiceReference sr,
        final ConfigurationImpl cfg )
    {
        // guard against NPE for new configuration never updated
        if (props == null) {
            return;
        }

        ServiceReference[] plugins = null;
        try
        {
            String filter = "(|(!(cm.target=*))(cm.target=" + targetPid + "))";
            plugins = bundleContext.getServiceReferences( ConfigurationPlugin.class.getName(), filter );
        }
        catch ( InvalidSyntaxException ise )
        {
            // no filter, no exception ...
        }

        // abort early if there are no plugins
        if ( plugins == null || plugins.length == 0 )
        {
            return;
        }

        // sort the plugins by their service.cmRanking
        if ( plugins.length > 1 )
        {
            Arrays.sort( plugins, RankingComparator.CM_RANKING );
        }

        // call the plugins in order
        for ( int i = 0; i < plugins.length; i++ )
        {
            ServiceReference pluginRef = plugins[i];
            ConfigurationPlugin plugin = ( ConfigurationPlugin ) bundleContext.getService( pluginRef );
            if ( plugin != null )
            {
                try
                {
                    plugin.modifyConfiguration( sr, props );
                }
                catch ( Throwable t )
                {
                    log( LogService.LOG_ERROR, "Unexpected problem calling configuration plugin "
                        + toString( pluginRef ), t );
                }
                finally
                {
                    // ensure ungetting the plugin
                    bundleContext.ungetService( pluginRef );
                }
                cfg.setAutoProperties( props, false );
            }
        }
    }


    /**
     * Creates a PID for the given factoryPid
     *
     * @param factoryPid
     * @return
     */
    private static String createPid( String factoryPid )
    {
        Random ng = numberGenerator;
        if ( ng == null )
        {
            // FELIX-2771 Secure Random not available on Mika
            try
            {
                ng = new SecureRandom();
            }
            catch ( Throwable t )
            {
                // fall back to Random
                ng = new Random();
            }
        }

        byte[] randomBytes = new byte[16];
        ng.nextBytes( randomBytes );
        randomBytes[6] &= 0x0f; /* clear version */
        randomBytes[6] |= 0x40; /* set to version 4 */
        randomBytes[8] &= 0x3f; /* clear variant */
        randomBytes[8] |= 0x80; /* set to IETF variant */

        StringBuffer buf = new StringBuffer( factoryPid.length() + 1 + 36 );

        // prefix the new pid with the factory pid
        buf.append( factoryPid ).append( "." );

        // serialize the UUID into the buffer
        for ( int i = 0; i < randomBytes.length; i++ )
        {

            if ( i == 4 || i == 6 || i == 8 || i == 10 )
            {
                buf.append( '-' );
            }

            int val = randomBytes[i] & 0xff;
            buf.append( Integer.toHexString( val >> 4 ) );
            buf.append( Integer.toHexString( val & 0xf ) );
        }

        return buf.toString();
    }


    boolean isLogEnabled( int level )
    {
        return level <= logLevel;
    }


    void log( int level, String message, Throwable t )
    {
        // log using the LogService if available
        Object log = logTracker.getService();
        if ( log != null )
        {
            ( ( LogService ) log ).log( getServiceReference(), level, message, t );
            return;
        }

        // Otherwise only log if more serious than the configured level
        if ( isLogEnabled( level ) )
        {
            String code;
            switch ( level )
            {
                case LogService.LOG_INFO:
                    code = "*INFO *";
                    break;

                case LogService.LOG_WARNING:
                    code = "*WARN *";
                    break;

                case LogService.LOG_ERROR:
                    code = "*ERROR*";
                    break;

                case LogService.LOG_DEBUG:
                default:
                    code = "*DEBUG*";
            }

            System.err.println( code + " " + message );
            if ( t != null )
            {
                t.printStackTrace( System.err );
            }
        }
    }


    /**
     * Returns the <code>service.pid</code> property of the service reference as
     * an array of strings or <code>null</code> if the service reference does
     * not have a service PID property.
     * <p>
     * The service.pid property may be a single string, in which case a single
     * element array is returned. If the property is an array of string, this
     * array is returned. If the property is a collection it is assumed to be a
     * collection of strings and the collection is converted to an array to be
     * returned. Otherwise (also if the property is not set) <code>null</code>
     * is returned.
     *
     * @throws NullPointerException
     *             if reference is <code>null</code>
     * @throws ArrayStoreException
     *             if the service pid is a collection and not all elements are
     *             strings.
     */
    static String[] getServicePid( ServiceReference reference )
    {
        Object pidObj = reference.getProperty( Constants.SERVICE_PID );
        if ( pidObj instanceof String )
        {
            return new String[]
                { ( String ) pidObj };
        }
        else if ( pidObj instanceof String[] )
        {
            return ( String[] ) pidObj;
        }
        else if ( pidObj instanceof Collection )
        {
            Collection pidCollection = ( Collection ) pidObj;
            return ( String[] ) pidCollection.toArray( new String[pidCollection.size()] );
        }

        return null;
    }


    static String toString( ServiceReference ref )
    {
        String[] ocs = ( String[] ) ref.getProperty( "objectClass" );
        StringBuffer buf = new StringBuffer("[");
        for ( int i = 0; i < ocs.length; i++ )
        {
            buf.append(ocs[i]);
            if ( i < ocs.length - 1 )
                buf.append(", ");
        }

        buf.append( ", id=" ).append( ref.getProperty( Constants.SERVICE_ID ) );

        Bundle provider = ref.getBundle();
        if ( provider != null )
        {
            buf.append( ", bundle=" ).append( provider.getBundleId() );
        }
        else
        {
            buf.append( ", unregistered" );
        }

        buf.append( "]" );
        return buf.toString();
    }


    void handleCallBackError( final Throwable error, final ServiceReference target, final ConfigurationImpl config )
    {
        if ( error instanceof ConfigurationException )
        {
            final ConfigurationException ce = ( ConfigurationException ) error;
            if ( ce.getProperty() != null )
            {
                log( LogService.LOG_ERROR, toString( target ) + ": Updating configuration property " + ce.getProperty()
                    + " caused a problem: " + ce.getReason(), ce );
            }
            else
            {
                log( LogService.LOG_ERROR, toString( target ) + ": Updating configuration caused a problem: "
                    + ce.getReason(), ce );
            }
        }
        else
        {
            {
                log( LogService.LOG_ERROR, toString( target ) + ": Unexpected problem updating " + config, error );
            }

        }
    }


    /**
     * Checks whether the bundle is allowed to receive the configuration
     * with the given location binding.
     * <p>
     * This method implements the logic defined CM 1.4 / 104.4.1:
     * <ul>
     * <li>If the location is <code>null</code> (the configuration is not
     * bound yet), assume the bundle is allowed</li>
     * <li>If the location is a single location (no leading "?"), require
     * the bundle's location to match</li>
     * <li>If the location is a multi-location (leading "?"), assume the
     * bundle is allowed if there is no security manager. If there is a
     * security manager, check whether the bundle has "target" permission
     * on this location.</li>
     * </ul>
     */
    boolean canReceive( final Bundle bundle, final String location )
    {
        if ( location == null )
        {
            return true;
        }
        else if ( location.startsWith( "?" ) )
        {
            // multi-location
            if ( System.getSecurityManager() != null )
            {
                return bundle.hasPermission( new ConfigurationPermission( location, ConfigurationPermission.TARGET ) );
            }
            return true;
        }
        else
        {
            // single location, must match
            return location.equals( bundle.getLocation() );
        }
    }

    // ---------- inner classes

    private ServiceHelper createServiceHelper( ConfigurationImpl config )
    {
        if ( config.getFactoryPid() == null )
        {
            return new ManagedServiceHelper( config );
        }
        return new ManagedServiceFactoryHelper( config );
    }

    private abstract class ServiceHelper
    {
        protected final ConfigurationImpl config;

        private final Dictionary properties;

        protected ServiceHelper( ConfigurationImpl config )
        {
            this.config = config;
            this.properties = config.getProperties( true );
        }

        final ServiceReference[] getServices( )
        {
            try
            {
                ServiceReference[] refs = doGetServices();
                if ( refs != null && refs.length > 1 )
                {
                    Arrays.sort( refs, RankingComparator.SRV_RANKING );
                }
                return refs;
            }
            catch ( InvalidSyntaxException ise )
            {
                log( LogService.LOG_ERROR, "Service selection filter is invalid to update " + config, ise );
            }
            return null;
        }


        protected abstract ServiceReference[] doGetServices() throws InvalidSyntaxException;


        abstract void provide( ServiceReference service );


        abstract void remove( ServiceReference service );


        protected Dictionary getProperties( String targetPid, ServiceReference service )
        {
            Dictionary props = new CaseInsensitiveDictionary( this.properties );
            callPlugins( props, targetPid, service, config );
            return props;
        }
    }

    private class ManagedServiceHelper extends ServiceHelper
    {

        protected ManagedServiceHelper( ConfigurationImpl config )
        {
            super( config );
        }


        public ServiceReference[] doGetServices() throws InvalidSyntaxException
        {
            return bundleContext.getServiceReferences( ManagedService.class.getName(), "(" + Constants.SERVICE_PID
                + "=" + config.getPid() + ")" );
        }


        public void provide( ServiceReference service )
        {
            ManagedService srv = ( ManagedService ) bundleContext.getService( service );
            if ( srv != null )
            {
                try
                {
                    Dictionary props = getProperties( this.config.getPid(), service );
                    srv.updated( props );
                }
                catch ( Throwable t )
                {
                    handleCallBackError( t, service, config );
                }
                finally
                {
                    bundleContext.ungetService( service );
                }
            }
        }


        public void remove( ServiceReference service )
        {
            ManagedService srv = ( ManagedService ) bundleContext.getService( service );
            try
            {
                srv.updated( null );
            }
            catch ( Throwable t )
            {
                handleCallBackError( t, service, config );
            }
            finally
            {
                bundleContext.ungetService( service );
            }
        }

    }

    private class ManagedServiceFactoryHelper extends ServiceHelper
    {

        protected ManagedServiceFactoryHelper( ConfigurationImpl config )
        {
            super( config );
            // TODO Auto-generated constructor stub
        }


        public ServiceReference[] doGetServices() throws InvalidSyntaxException
        {
            return bundleContext.getServiceReferences( ManagedServiceFactory.class.getName(), "("
                + Constants.SERVICE_PID + "=" + config.getFactoryPid() + ")" );
        }


        public void provide( ServiceReference service )
        {
            ManagedServiceFactory srv = ( ManagedServiceFactory ) bundleContext.getService( service );
            if ( srv != null )
            {
                try
                {
                    Dictionary props = getProperties( this.config.getFactoryPid(), service );
                    srv.updated( config.getPid(), props );
                }
                catch ( Throwable t )
                {
                    handleCallBackError( t, service, config );
                }
                finally
                {
                    bundleContext.ungetService( service );
                }
            }
        }


        public void remove( ServiceReference service )
        {
            ManagedServiceFactory srv = ( ManagedServiceFactory ) bundleContext.getService( service );
            try
            {
                srv.deleted( config.getPid() );
            }
            catch ( Throwable t )
            {
                handleCallBackError( t, service, config );
            }
            finally
            {
                bundleContext.ungetService( service );
            }
        }

    }

    /**
     * The <code>ManagedServiceUpdate</code> updates a freshly registered
     * <code>ManagedService</code> with a specific configuration. If a
     * ManagedService is registered with multiple PIDs an instance of this
     * class is used for each registered PID.
     */
    private class ManagedServiceUpdate implements Runnable
    {
        private final String pid;

        private final ServiceReference sr;

        private final ManagedService service;

        private final ConfigurationImpl config;

        private final Dictionary rawProperties;

        private final long lastModificationTime;

        ManagedServiceUpdate( String pid, ServiceReference sr, ManagedService service )
        {
            this.pid = pid;
            this.sr = sr;
            this.service = service;

            // get or load configuration for the pid
            ConfigurationImpl config = null;
            Dictionary rawProperties = null;
            long lastModificationTime = -1;
            try
            {
                config = getExistingConfiguration( pid );
                if ( config != null )
                {
                    synchronized ( config )
                    {
                        rawProperties = config.getProperties( true );
                        lastModificationTime = config.getLastModificationTime();
                    }
                }
            }
            catch ( IOException ioe )
            {
                log( LogService.LOG_ERROR, "Error loading configuration for " + pid, ioe );
            }

            this.config = config;
            this.rawProperties = rawProperties;
            this.lastModificationTime = lastModificationTime;
        }


        public void run()
        {
            // only update configuration if lastModificationTime is less than
            // lastUpdateTime
            Dictionary properties = rawProperties;
            if ( properties != null && config != null && lastModificationTime < config.getLastUpdatedTime() )
            {
                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG, "Configuration " + config.getPid() + " at modification #"
                        + config.getLastModificationTime() + " has already been updated to update #"
                        + config.getLastUpdatedTime() + ", nothing to be done anymore.", null );
                }
                return;
            }

            // check configuration and call plugins if existing
            if ( config != null )
            {
                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG, "Updating configuration " + pid + " to modification #"
                        + config.getLastModificationTime(), null );
                }

                Bundle serviceBundle = sr.getBundle();
                if ( serviceBundle == null )
                {
                    if ( isLogEnabled( LogService.LOG_INFO ) )
                    {
                        log( LogService.LOG_INFO, "Service for PID " + pid
                            + " seems to already have been unregistered, not updating with configuration", null );
                    }
                    return;
                }

                // CM 1.4 / 104.13.2.2
                if ( !canReceive( serviceBundle, config.getBundleLocation() ) )
                {
                    log( LogService.LOG_ERROR,
                        "Cannot use configuration " + pid + " for " + ConfigurationManager.toString( sr )
                            + ": No visibility to configuration bound to " + config.getBundleLocation(), null );
                    return;
                }

                // 104.4.2 Dynamic Binding
                config.tryBindLocation( serviceBundle.getLocation() );

                // prepare the configuration for the service (call plugins)
                callPlugins( properties, pid, sr, config );
            }
            else
            {
                // 104.5.3 ManagedService.updated must be called with null
                // if no configuration is available
                properties = null;
            }

            // update the service with the configuration
            try
            {
                service.updated( properties );
            }
            catch ( Throwable t )
            {
                handleCallBackError( t, sr, config );
            }

            // update the lastUpdatedTime if there is configuration
            if ( config != null && properties != null )
            {
                config.setLastUpdatedTime( lastModificationTime );
                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG, "Updated configuration " + config.getPid() + " to update #"
                        + config.getLastUpdatedTime(), null );
                }
            }
        }

        public String toString()
        {
            return "ManagedService Update: pid=" + pid;
        }
    }

    /**
     * The <code>ManagedServiceFactoryUpdate</code> updates a freshly
     * registered <code>ManagedServiceFactory</code> with a specific
     * configuration. If a ManagedServiceFactory is registered with
     * multiple PIDs an instance of this class is used for each registered
     * PID.
     */
    private class ManagedServiceFactoryUpdate implements Runnable
    {
        private final String factoryPid;

        private final ServiceReference sr;

        private final ManagedServiceFactory service;

        private final Map configs;

        private final Map stamps;

        ManagedServiceFactoryUpdate( String factoryPid, ServiceReference sr, ManagedServiceFactory service )
        {
            this.factoryPid = factoryPid;
            this.sr = sr;
            this.service = service;

            Factory factory = null;
            Map configs = null;
            Map stamps = null;
            try
            {
                factory = getFactory( factoryPid );
                if (factory != null) {
                    configs = new HashMap();
                    stamps = new HashMap();
                    for ( Iterator pi = factory.getPIDs().iterator(); pi.hasNext(); )
                    {
                        final String pid = ( String ) pi.next();
                        ConfigurationImpl cfg;
                        try
                        {
                            cfg = getExistingConfiguration( pid );
                        }
                        catch ( IOException ioe )
                        {
                            log( LogService.LOG_ERROR, "Error loading configuration for " + pid, ioe );
                            continue;
                        }

                        // sanity check on the configuration
                        if ( cfg == null )
                        {
                            log( LogService.LOG_ERROR, "Configuration " + pid + " referred to by factory " + factoryPid
                                + " does not exist", null );
                            factory.removePID( pid );
                            factory.storeSilently();
                            continue;
                        }
                        else if ( cfg.isNew() )
                        {
                            // Configuration has just been created but not yet updated
                            // we currently just ignore it and have the update mechanism
                            // provide the configuration to the ManagedServiceFactory
                            // As of FELIX-612 (not storing new factory configurations)
                            // this should not happen. We keep this for added stability
                            // but raise the logging level to error.
                            log( LogService.LOG_ERROR, "Ignoring new configuration pid=" + pid, null );
                            continue;
                        }
                        else if ( !factoryPid.equals( cfg.getFactoryPid() ) )
                        {
                            log( LogService.LOG_ERROR, "Configuration " + pid + " referred to by factory " + factoryPid
                                + " seems to belong to factory " + cfg.getFactoryPid(), null );
                            factory.removePID( pid );
                            factory.storeSilently();
                            continue;
                        }

                        // get the configuration properties for later
                        synchronized ( cfg )
                        {
                            configs.put( cfg, cfg.getProperties( true ) );
                            stamps.put( cfg, new Long( cfg.getLastModificationTime() ) );
                        }
                    }
                }
            }
            catch ( IOException ioe )
            {
                log( LogService.LOG_ERROR, "Cannot get factory mapping for factory PID " + factoryPid, ioe );
            }

            this.configs = configs;
            this.stamps = stamps;
        }


        public void run()
        {
            Bundle serviceBundle = sr.getBundle();
            if ( serviceBundle == null )
            {
                if ( isLogEnabled( LogService.LOG_INFO ) )
                {
                    log( LogService.LOG_INFO, "ManagedServiceFactory for factory PID " + factoryPid
                        + " seems to already have been unregistered, not updating with factory", null );
                }
                return;
            }

            for ( Iterator ci=configs.entrySet().iterator(); ci.hasNext(); )
            {
                final Map.Entry entry = (Map.Entry) ci.next();
                final ConfigurationImpl cfg = (ConfigurationImpl) entry.getKey();
                final Dictionary properties = (Dictionary) entry.getValue();
                final long lastModificationTime = ( ( Long ) stamps.get( cfg ) ).longValue();

                if ( lastModificationTime <= cfg.getLastUpdatedTime() )
                {
                    if ( isLogEnabled( LogService.LOG_DEBUG ) )
                    {
                        log( LogService.LOG_DEBUG, "Configuration " + cfg.getPid() + " at modification #"
                            + cfg.getLastModificationTime() + " has already been updated to update #"
                            + cfg.getLastUpdatedTime() + ", nothing to be done anymore.", null );
                    }
                    continue;
                }

                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG, "Updating configuration " + cfg.getPid() + " to modification #"
                        + cfg.getLastModificationTime(), null );
                }

                // CM 1.4 / 104.13.2.1
                if ( !canReceive( serviceBundle, cfg.getBundleLocation() ) )
                {
                    log( LogService.LOG_ERROR,
                        "Cannot use configuration " + cfg.getPid() + " for " + ConfigurationManager.toString( sr )
                            + ": No visibility to configuration bound to " + cfg.getBundleLocation(), null );
                    continue;
                }

                // 104.4.2 Dynamic Binding
                cfg.tryBindLocation( serviceBundle.getLocation() );

                // prepare the configuration for the service (call plugins)
                // call the plugins with cm.target set to the service's factory PID
                // (clarification in Section 104.9.1 of Compendium 4.2)
                callPlugins( properties, factoryPid, sr, cfg );

                // update the service with the configuration (if non-null)
                if ( properties != null )
                {
                    if ( isLogEnabled( LogService.LOG_DEBUG ) )
                    {
                        log( LogService.LOG_DEBUG, sr + ": Updating configuration pid=" + cfg.getPid(), null );
                    }

                    try
                    {
                        service.updated( cfg.getPid(), properties );
                    }
                    catch ( Throwable t )
                    {
                        handleCallBackError( t, sr, cfg );
                    }

                    // update the lastUpdatedTime
                    cfg.setLastUpdatedTime( lastModificationTime );

                    if ( isLogEnabled( LogService.LOG_DEBUG ) )
                    {
                        log( LogService.LOG_DEBUG, "Updated configuration " + cfg.getPid() + " to update #"
                            + cfg.getLastUpdatedTime(), null );
                    }
                }
            }
        }


        public String toString()
        {
            return "ManagedServiceFactory Update: factoryPid=" + factoryPid;
        }
    }


    /**
     * The <code>UpdateConfiguration</code> is used to update
     * <code>ManagedService[Factory]</code> services with the configuration
     * they are subscribed to. This may cause the configuration to be
     * supplied to multiple services.
     */
    private class UpdateConfiguration implements Runnable
    {

        private final ConfigurationImpl config;
        private final ServiceHelper helper;
        private final long lastModificationTime;


        UpdateConfiguration( final ConfigurationImpl config )
        {
            this.config = config;
            synchronized ( config )
            {
                this.helper = createServiceHelper( config );
                this.lastModificationTime = config.getLastModificationTime();
            }
        }


        public void run()
        {
            if ( lastModificationTime <= config.getLastUpdatedTime() )
            {
                if ( isLogEnabled( LogService.LOG_DEBUG ) )
                {
                    log( LogService.LOG_DEBUG,
                        "Configuration " + config.getPid() + " at modification #" + config.getLastModificationTime()
                            + " has already been updated to update #" + config.getLastUpdatedTime()
                            + ", nothing to be done anymore.", null );
                }
                return;
            }

            if ( isLogEnabled( LogService.LOG_DEBUG ) )
            {
                log(
                    LogService.LOG_DEBUG,
                    "Updating configuration " + config.getPid() + " to modification #"
                        + config.getLastModificationTime(), null );
            }

            final ServiceReference[] srList = helper.getServices();
            if ( srList != null )
            {
                // optionally bind dynamically to the first service
                config.tryBindLocation( srList[0].getBundle().getLocation() );

                final String configBundleLocation = config.getBundleLocation();

                // provide configuration to all services from the
                // correct bundle
                for ( int i = 0; i < srList.length; i++ )
                {
                    final ServiceReference ref = srList[i];

                    // CM 1.4 / 104.13.2.2
                    if ( !canReceive( ref.getBundle(), configBundleLocation ) )
                    {
                        log( LogService.LOG_ERROR, "Cannot use configuration " + config.getPid() + " for "
                            + ConfigurationManager.toString( ref ) + ": No visibility to configuration bound to "
                            + configBundleLocation, null );
                        continue;
                    }

                    helper.provide( ref );

                    if ( isLogEnabled( LogService.LOG_DEBUG ) )
                    {
                        log( LogService.LOG_DEBUG,
                            "Updated configuration " + config.getPid() + " to update #" + config.getLastUpdatedTime(),
                            null );
                    }
                }

                // update the lastUpdatedTime
                config.setLastUpdatedTime( lastModificationTime );
            }
        }


        public String toString()
        {
            return "Update: pid=" + config.getPid();
        }
    }


    /**
     * The <code>DeleteConfiguration</code> class is used to inform
     * <code>ManagedService[Factory]</code> services of a configuration
     * being deleted.
     */
    private class DeleteConfiguration implements Runnable
    {

        private final ConfigurationImpl config;
        private final String configLocation;


        DeleteConfiguration( ConfigurationImpl config )
        {
            /*
             * NOTE: We keep the configuration because it might be cleared just
             * after calling this method. The pid and factoryPid fields are
             * final and cannot be reset.
             */
            this.config = config;
            this.configLocation = config.getBundleLocation();
        }


        public void run()
        {
            final String pid = config.getPid();
            final String factoryPid = config.getFactoryPid();
            final ServiceHelper helper = createServiceHelper( config );

            ServiceReference[] srList = helper.getServices( );
            if ( srList != null )
            {
                for ( int i = 0; i < srList.length; i++ )
                {
                    final ServiceReference sr = srList[i];
                    if ( canReceive( sr.getBundle(), configLocation ) )
                    {
                        helper.remove( sr );
                    }
                }
            }

            if ( factoryPid != null )
            {
                // remove the pid from the factory
                try
                {
                    Factory factory = getFactory( factoryPid );
                    factory.removePID( pid );
                    factory.store();
                }
                catch ( IOException ioe )
                {
                    log( LogService.LOG_ERROR, "Failed removing " + pid + " from the factory " + factoryPid, ioe );
                }
            }
        }

        public String toString()
        {
            return "Delete: pid=" + config.getPid();
        }
    }

    private class LocationChanged implements Runnable
    {
        private final ConfigurationImpl config;
        private final String oldLocation;


        LocationChanged( ConfigurationImpl config, String oldLocation )
        {
            this.config = config;
            this.oldLocation = oldLocation;
        }


        public void run()
        {
            ServiceHelper helper = createServiceHelper( this.config );
            ServiceReference[] srList = helper.getServices( );
            if ( srList != null )
            {
                // make sure the config is dynamically bound to the first
                // service if it has been unbound causing this update
                config.tryBindLocation( srList[0].getBundle().getLocation() );

                for ( int i = 0; i < srList.length; i++ )
                {
                    final ServiceReference sr = srList[i];
                    final boolean wasVisible = canReceive( sr.getBundle(), oldLocation );
                    final boolean isVisible = canReceive( sr.getBundle(), config.getBundleLocation() );
                    if ( wasVisible && !isVisible )
                    {
                        // call deleted method
                        helper.remove( sr );
                    }
                    else if ( !wasVisible && isVisible )
                    {
                        // call updated method
                        helper.provide( sr );
                    }
                }
            }
        }


        public String toString()
        {
            return "Location Changed (pid=" + config.getPid() + "): " + oldLocation + " ==> "
                + config.getBundleLocation();
        }
    }

    private class FireConfigurationEvent implements Runnable
    {
        private final int type;

        private final String pid;

        private final String factoryPid;

        private final ServiceReference[] listenerReferences;

        private final ConfigurationListener[] listeners;

        private final Bundle[] listenerProvider;


        private FireConfigurationEvent( final int type, final String pid, final String factoryPid)
        {
            this.type = type;
            this.pid = pid;
            this.factoryPid = factoryPid;

            final ServiceReference[] srs = configurationListenerTracker.getServiceReferences();
            if ( srs == null || srs.length == 0 )
            {
                this.listenerReferences = null;
                this.listeners = null;
                this.listenerProvider = null;
            }
            else
            {
                this.listenerReferences = srs;
                this.listeners = new ConfigurationListener[srs.length];
                this.listenerProvider = new Bundle[srs.length];
                for ( int i = 0; i < srs.length; i++ )
                {
                    this.listeners[i] = ( ConfigurationListener ) configurationListenerTracker.getService( srs[i] );
                    this.listenerProvider[i] = srs[i].getBundle();
                }
            }
        }


        boolean hasConfigurationEventListeners()
        {
            return this.listenerReferences != null;
        }


        String getTypeName()
        {
            switch ( type )
            {
                case ConfigurationEvent.CM_DELETED:
                    return "CM_DELETED";
                case ConfigurationEvent.CM_UPDATED:
                    return "CM_UPDATED";
                case ConfigurationEvent.CM_LOCATION_CHANGED:
                    return "CM_LOCATION_CHANGED";
                default:
                    return "<UNKNOWN(" + type + ")>";
            }
        }


        public void run()
        {
            final String typeName = getTypeName();
            final ConfigurationEvent event = new ConfigurationEvent( getServiceReference(), type, factoryPid, pid );

            for ( int i = 0; i < listeners.length; i++ )
            {
                if ( listenerProvider[i].getState() == Bundle.ACTIVE )
                {
                    if ( isLogEnabled( LogService.LOG_DEBUG ) )
                    {
                        log( LogService.LOG_DEBUG, "Sending " + typeName + " event for " + pid + " to "
                            + ConfigurationManager.toString( listenerReferences[i] ), null );
                    }

                    try
                    {
                        listeners[i].configurationEvent( event );
                    }
                    catch ( Throwable t )
                    {
                        log( LogService.LOG_ERROR, "Unexpected problem delivery configuration event to "
                            + ConfigurationManager.toString( listenerReferences[i] ), t );
                    }
                }
            }
        }

        public String toString()
        {
            return "Fire ConfigurationEvent: pid=" + pid;
        }
    }

    private static class ManagedServiceTracker extends ServiceTracker
    {

        private final ConfigurationManager cm;


        ManagedServiceTracker( ConfigurationManager cm )
        {
            super( cm.bundleContext, ManagedService.class.getName(), null );
            this.cm = cm;
            open();
        }


        public Object addingService( ServiceReference reference )
        {
            Object serviceObject = super.addingService( reference );

            // configure the managed service
            if ( serviceObject instanceof ManagedService )
            {
                cm.configure(reference, ( ManagedService ) serviceObject);
            }
            else
            {
                cm.log( LogService.LOG_WARNING, "Service " + serviceObject + " is not a ManagedService", null );
            }

            return serviceObject;
        }
    }

    private static class ManagedServiceFactoryTracker extends ServiceTracker
    {
        private final ConfigurationManager cm;


        ManagedServiceFactoryTracker( ConfigurationManager cm )
        {
            super( cm.bundleContext, ManagedServiceFactory.class.getName(), null );
            this.cm = cm;
            open();
        }


        public Object addingService( ServiceReference reference )
        {
            Object serviceObject = super.addingService( reference );

            // configure the managed service factory
            if ( serviceObject instanceof ManagedServiceFactory )
            {
                cm.configure( reference, ( ManagedServiceFactory ) serviceObject );
            }
            else
            {
                cm.log( LogService.LOG_WARNING, "Service " + serviceObject + " is not a ManagedServiceFactory", null );
            }

            return serviceObject;
        }
    }


}
