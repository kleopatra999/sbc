/*******************************************************************************
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc, Eolos IT Corp and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 *******************************************************************************/
package org.restcomm.sbc;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.interpol.ConfigurationInterpolator;


import org.apache.log4j.Logger;
import org.restcomm.sbc.dao.ConnectorsDao;
import org.restcomm.sbc.dao.DaoManager;
import org.restcomm.sbc.identity.IdentityContext;
import org.restcomm.sbc.bo.Connector;
import org.restcomm.sbc.bo.shiro.ShiroResources;
import org.restcomm.sbc.configuration.RestcommConfiguration;
import org.restcomm.sbc.loader.ObjectFactory;
import org.restcomm.sbc.loader.ObjectInstantiationException;
import org.restcomm.sbc.managers.Monitor;
import org.restcomm.sbc.managers.NetworkManager;
import org.restcomm.sbc.managers.jmx.JMXProvider;
import org.restcomm.sbc.managers.jmx.JMXProviderFactory;

import com.typesafe.config.ConfigFactory;


public final class Bootstrapper extends SipServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(Bootstrapper.class);

   
    
    public Bootstrapper() {
        super();
        Thread.setDefaultUncaughtExceptionHandler(new MyExceptionHandler());
    }

    @Override
    public void destroy() {
        
    }

    private String home(final ServletConfig config) {
        final ServletContext context = config.getServletContext();
        final String path = context.getRealPath("/");
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        } else {
            return path;
        }
    }

    @Override
    public void init(final ServletConfig config) throws ServletException {
        final ServletContext context = config.getServletContext();
        
        final String path = context.getRealPath("WEB-INF/conf/sbc.xml");
        // Initialize the configuration interpolator.
        final ConfigurationStringLookup strings = new ConfigurationStringLookup();
        strings.addProperty("home", home(config));
        strings.addProperty("uri", uri(config));
        ConfigurationInterpolator.registerGlobalLookup("sbc", strings);
        // Load the RestComm configuration file.
        Configuration xml = null;
        try {
            xml = new XMLConfiguration(path);
        } catch (final ConfigurationException exception) {
            LOG.error(exception);
        }
        xml.setProperty("runtime-settings.home-directory", home(config));
        xml.setProperty("runtime-settings.root-uri", uri(config));
        context.setAttribute(Configuration.class.getName(), xml);
        // Initialize global dependencies.
        final ClassLoader loader = getClass().getClassLoader();
        // Create the actor system.
        //final Config settings = ConfigFactory.load();
        ConfigFactory.load();
        // Create the storage system.
        DaoManager storage = null;
        try {
            storage = storage(xml, loader);
        } catch (final ObjectInstantiationException exception) {
            throw new ServletException(exception);
        }
        
        context.setAttribute(DaoManager.class.getName(), storage);
        ShiroResources.getInstance().set(DaoManager.class, storage);
        ShiroResources.getInstance().set(Configuration.class, xml.subset("runtime-settings"));
       
        // Create high-level restcomm configuration
        RestcommConfiguration.createOnce(xml);
        // Initialize identityContext
        IdentityContext identityContext = new IdentityContext(xml);
        context.setAttribute(IdentityContext.class.getName(), identityContext);
        try {
        	//launchJMXServer();
			bindConnectors(storage);
			
		} catch (Exception e) {
			LOG.error(e);
		}
       
        Version.printVersion();
        
        Monitor monitor=Monitor.getMonitor();
        monitor.start();
       
        
        
    }
    
   
    private void bindConnectors(DaoManager storage) throws Exception {
    	
    	JMXProvider jmxManager=null;
    	boolean status;
    	jmxManager = JMXProviderFactory.getJMXProvider();
		
    	ConnectorsDao dao=storage.getConnectorsDao();
    	for(Connector connector:dao.getConnectors()) {
    		String npoint=connector.getPoint();
    		String ipAddress=NetworkManager.getIpAddress(npoint);
    		if(connector.getState()==Connector.State.UP) {
	    		status=jmxManager.addSipConnector(ipAddress, connector.getPort(), connector.getTransport().toString());
	    		if(status) {
		    		if(LOG.isDebugEnabled()) {
		    			LOG.debug("Binding Connector on "+npoint+":"+ipAddress+":"+connector.getPort()+"/"+connector.getTransport().toString());
		    		}
	    		}	
	    		else {
		    		if(LOG.isDebugEnabled()) {
		    			LOG.debug("CANNOT Bind Connector on "+npoint+":"+ipAddress+":"+connector.getPort()+"/"+connector.getTransport().toString());
		    		}
	    		}
    		}
    	}
    	
    }
    

    private DaoManager storage(final Configuration configuration, final ClassLoader loader) throws ObjectInstantiationException {
        final String classpath = configuration.getString("dao-manager[@class]");
        final DaoManager daoManager = (DaoManager) new ObjectFactory(loader).getObjectInstance(classpath);
        daoManager.configure(configuration.subset("dao-manager"));
        daoManager.start();
        LOG.info("DaoManager started");
        return daoManager;
    }
    

    private String uri(final ServletConfig config) {
        return config.getServletContext().getContextPath();
    }
    
    class MyExceptionHandler implements java.lang.Thread.UncaughtExceptionHandler {

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			LOG.error(t.getName(), e);
			
		}
    	
    }
}
