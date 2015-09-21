package com.dontocsata.geospatial.plugin;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by ray.douglass on 9/18/15.
 */
public class PluginManager {

	private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

	private EventBus eventBus;
	private GenericApplicationContext context;
	private File pluginDirectory = new File("plugins");

	private Map<Class<?>, PluginWrapper> plugins = new LinkedHashMap<>();

	public PluginManager(EventBus eventBus, GenericApplicationContext context) {
		this.eventBus = eventBus;
		this.context = context;
		this.eventBus.register(this);
	}

	/**
	 * Load all of the plugins. Returns the plugins that failed to load.
	 */
	public Collection<PluginWrapper> loadPlugins() throws IOException {
		//System plugins
		findPlugins(null, true).stream().forEach(pw -> plugins.put(pw.getPluginClass(), pw));
		//Other plugins
		if (pluginDirectory.exists() && pluginDirectory.isDirectory()) {
			for (File file : pluginDirectory.listFiles()) {
				URLClassLoader classLoader = new URLClassLoader(new URL[]{file.toURI().toURL()}, this.getClass().getClassLoader());
				findPlugins(classLoader, false).stream().forEach(pw -> plugins.put(pw.getPluginClass(), pw));
			}
		}
		return plugins.values().stream().filter(pw -> pw.getState() == PluginState.ERROR_LOADING).collect(Collectors.toList());
	}

	public Collection<PluginWrapper> getPlugins(PluginState state) {
		return plugins.values().stream().filter(pw -> pw.getState() == state).collect(Collectors.toList());
	}

	/**
	 * Start all of the loaded plugins. Returns the plugins that failed to started
	 */
	public Collection<PluginWrapper> startPlugins() {
		Collection<PluginWrapper> toRet = new ArrayList<>();
		for (PluginWrapper pw : plugins.values()) {
			if (pw.getState() == PluginState.LOADED) {
				try {
					pw.start();
				} catch (Exception ex) {
					log.warn("Failed to start plugin=" + pw.getPluginClass(), ex);
					pw.setState(PluginState.ERROR_STARTING);
					toRet.add(pw);
				}
			}
		}
		return toRet;
	}

	public void stopPlugins() {
		for (PluginWrapper pw : plugins.values()) {
			if (pw.getState() == PluginState.STARTED) {
				try {
					pw.stop();
				} catch (Exception ex) {
					log.warn("Failed to start plugin=" + pw.getPluginClass(), ex);
				}
				pw.setState(PluginState.STOPPED);
			}
		}
	}

	private Collection<PluginWrapper> findPlugins(URLClassLoader classLoader, boolean systemPlugins) {

		Collection<PluginWrapper> result = new ArrayList<>();
		Reflections reflections = systemPlugins ? new Reflections(new ConfigurationBuilder().forPackages("com.dontocsata.geospatial.handlers")) : new Reflections(new ConfigurationBuilder()
				.addClassLoader(classLoader).setUrls(classLoader.getURLs()));
		Set<Class<?>> klasses = reflections.getTypesAnnotatedWith(Plugin.class);
		if (!systemPlugins && klasses.size() > 1) {
			log.warn("Non-system plugin declares multiple @Plugin annotation. Offending class={}", klasses);
			return klasses.stream().map(klass -> {
				Plugin plugin = klass.getAnnotation(Plugin.class);
				PluginWrapper pw = new PluginWrapper(this, plugin, klass, PluginState.ERROR_LOADING);
				eventBus.post(new PluginStateChangeEvent(pw, PluginState.NEW, pw.getState()));
				return pw;
			}).collect(Collectors.toList());
		}
		for (Class<?> klass : klasses) {
			Plugin plugin = klass.getAnnotation(Plugin.class);
			Collection<PluginRunner> runners = new ArrayList<>();
			try {
				for (Class<? extends PluginRunner> runnerClass : plugin.runners()) {
					if (!runnerClass.equals(Plugin.DEFAULT.class)) {
						PluginRunner runner = context.getBeanFactory().createBean(runnerClass);
						runners.add(runner);
					}
				}
				if (systemPlugins && runners.isEmpty()) {
					throw new IllegalStateException("System plugins must declare their PluginRunners. Offending Plugin: " + klass.getName());
				} else if (runners.isEmpty()) {
					Set<Class<? extends PluginRunner>> runnerClasses = reflections.getSubTypesOf(PluginRunner.class);
					for (Class<? extends PluginRunner> runnerClass : runnerClasses) {
						if (!runnerClass.equals(Plugin.DEFAULT.class)) {
							PluginRunner runner = context.getBeanFactory().createBean(runnerClass);
							runners.add(runner);
						}
					}
				}
				PluginWrapper wrapper = null;
				if (runners.isEmpty()) {
					wrapper = new PluginWrapper(this, plugin, klass, PluginState.ERROR_LOADING);
				} else {
					wrapper = new PluginWrapper(this, plugin, klass, runners);
				}
				eventBus.post(new PluginStateChangeEvent(wrapper, PluginState.NEW));
				log.info("Found plugin {}", wrapper);
				result.add(wrapper);
			} catch (Exception ex) {
				log.error("Error loading Plugin=" + klass, ex);
				PluginWrapper pw = new PluginWrapper(this, plugin, klass, PluginState.ERROR_LOADING);
				eventBus.post(new PluginStateChangeEvent(pw, PluginState.NEW));
				result.add(pw);
			}
		}

		return result;
	}

	EventBus getEventBus() {
		return eventBus;
	}

	@Subscribe
	public void subscribe(PluginStateChangeEvent event) {
		log.debug("{}", event);
	}

}
