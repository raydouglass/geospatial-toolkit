package com.dontocsata.geospatial.handlers;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dontocsata.geospatial.MenuItemDescriptor;
import com.dontocsata.geospatial.setup.MenuCommandHandler;
import com.lynden.gmapsfx.javascript.object.GoogleMap;
import com.lynden.gmapsfx.javascript.object.LatLong;

import javafx.scene.control.TextInputDialog;

@Component
public class ZoomController implements MenuCommandHandler {

	@Autowired
	private GoogleMap map;

	@Override
	public void invoke() throws Exception {
		System.out.println(map.fromLatLngToPoint(new LatLong(10, 0)));
		System.out.println(map.fromLatLngToPoint(new LatLong(11, 0)));
		Optional<String> result = new TextInputDialog(Integer.toString(map.getZoom())).showAndWait();
		if (result.isPresent()) {
			Integer i = Integer.parseInt(result.get());
			map.setZoom(i);
		}
	}

	@Override
	public MenuItemDescriptor getMenuItemDescriptor() {
		return new MenuItemDescriptor("File", "Zoom");
	}

}
