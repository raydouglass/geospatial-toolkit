package com.dontocsata.geospatial.plugin;

/**
 * Created by ray.douglass on 9/18/15.
 */
@Plugin(name = "TestPlugin", runners = TestPlugin.TestPluginRunner.class)
public class TestPlugin {

	public static class TestPluginRunner implements PluginRunner{

		@Override
		public void start() {

		}

		@Override
		public void stop() {

		}
	}
}