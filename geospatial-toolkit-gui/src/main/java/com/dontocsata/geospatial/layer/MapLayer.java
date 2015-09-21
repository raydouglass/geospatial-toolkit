package com.dontocsata.geospatial.layer;

import com.dontocsata.geospatial.GeoUtils;
import com.dontocsata.geospatial.GeometryException;
import com.dontocsata.geospatial.GeometryWrapper;
import com.dontocsata.geospatial.StreamUtils;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.lynden.gmapsfx.javascript.object.GoogleMap;
import com.lynden.gmapsfx.javascript.object.MVCArray;
import com.lynden.gmapsfx.javascript.object.MapShape;
import com.lynden.gmapsfx.javascript.object.Marker;
import com.lynden.gmapsfx.shapes.PolygonOptions;
import com.lynden.gmapsfx.shapes.Polyline;
import com.lynden.gmapsfx.shapes.PolylineOptions;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MapLayer {

	private String name;
	private List<GeometryWrapper> geometries;
	private Color color;
	private Multimap<MapLayerEventType, Consumer<MapLayerEvent>> listeners;

	private Geometry bounds;
	private Map<GoogleMap, Collection<Object>> mappedObjects;

	private MapLayer() {

	}

	private void init() {
		geometries = geometries.stream().map(StreamUtils.rethrow(g -> g.transformGeometry(GeoUtils.WGS84_SRID)))
				.collect(Collectors.toList());
		bounds = new GeometryCollection(geometries.stream().map(GeometryWrapper::getGeometry).collect(Collectors.toList()).toArray(new Geometry[0]), GeoUtils.WGS84_GEOMETRY_FACTORY)
				.getEnvelope();
		mappedObjects = new ConcurrentHashMap<>();
	}

	private void addListener(GoogleMap map, GeometryWrapper geom, Marker marker, MapShape shape) {
		for (MapLayerEventType type : listeners.keySet()) {
			map.addUIEventHandler(marker, type.getUiEventType(), jsObj -> {
				MapLayerEvent event = null;
				if (marker == null) {
					event = new MapLayerEvent(marker, geom);
				} else {
					event = new MapLayerEvent(shape, geom);
				}
				for (Consumer<MapLayerEvent> listener : listeners.get(type)) {
					listener.accept(event);
				}
			});
		}
	}

	public void addTo(GoogleMap map) throws GeometryException {
		Collection<Object> objects = new ArrayList<>();
		mappedObjects.put(map, objects);
		for (GeometryWrapper geom : geometries) {
			if (geom.isPoint()) {
				Marker marker = GeoUtils.convert((Point) geom.getGeometry());
				objects.add(marker);
				addListener(map, geom, marker, null);
				map.addMarker(marker);
			} else if (geom.isGeometryCollection()) {
				GeometryCollection gc = (GeometryCollection) geom.getGeometry();
				for (int i = 0; i < gc.getNumGeometries(); i++) {
					Geometry g = gc.getGeometryN(i);
					if (g instanceof Point) {
						Marker marker = GeoUtils.convert((Point) g);
						objects.add(marker);
						addListener(map, geom, marker, null);
						map.addMarker(marker);
					} else {
						MapShape ms = convert(g);
						objects.add(ms);
						addListener(map, geom, null, ms);
						map.addMapShape(ms);
					}
				}
			} else {
				MapShape ms = convert(geom.getGeometry());
				objects.add(ms);
				addListener(map, geom, null, ms);
				map.addMapShape(ms);
			}
		}
	}

	public void removeFrom(GoogleMap map) {
		for (Object o : mappedObjects.remove(map)) {
			if (o instanceof Marker) {
				map.removeMarker((Marker) o);
			} else if (o instanceof MapShape) {
				map.removeMapShape((MapShape) o);
			} else {
				throw new IllegalStateException("Object is not a Marker or MapShape: " + o);
			}
		}
	}

	private MapShape convert(Geometry geom) throws GeometryException {
		geom = GeoUtils.transform(geom, GeoUtils.WGS84_SRID);
		if (geom instanceof LineString) {
			LineString ls = (LineString) geom;
			PolylineOptions opts = new PolylineOptions();
			opts.path(toMvcArray(ls));
			opts.strokeColor(toRGBCode(color));
			return new Polyline(opts);
		} else if (geom instanceof Polygon) {
			Polygon p = (Polygon) geom;
			PolygonOptions opts = new PolygonOptions();
			MVCArray array = new MVCArray();
			array.push(toMvcArray(p.getExteriorRing()));
			for (int i = 0; i < p.getNumInteriorRing(); i++) {
				array.push(toMvcArray(p.getInteriorRingN(i)));
			}
			// opts.paths(array);
			opts.strokeColor(toRGBCode(color));
			opts.fillColor(toRGBCode(color.brighter()));
			com.lynden.gmapsfx.shapes.Polygon polygon = new com.lynden.gmapsfx.shapes.Polygon(opts);
			polygon.setPaths(array);
			return polygon;
		} else {
			throw new RuntimeException();
		}
	}

	private MVCArray toMvcArray(LineString ls) {
		MVCArray array = new MVCArray();
		Stream.of(ls.getCoordinates()).map(GeoUtils::convert).forEach(array::push);
		return array;
	}

	private static String toRGBCode(Color color) {
		return String.format("#%02X%02X%02X", (int) (color.getRed() * 255), (int) (color.getGreen() * 255),
				(int) (color.getBlue() * 255));
	}

	public Point getCenter() {
		return bounds.getCentroid();
	}

	public Geometry getBounds() {
		return bounds;
	}

	public Color getColor() {
		return color;
	}

	public String getName() {
		return name;
	}

	public static class Builder {

		private String name;
		private List<GeometryWrapper> geometries = new ArrayList<>();
		private Color color = Color.CORNFLOWERBLUE;
		private Multimap<MapLayerEventType, Consumer<MapLayerEvent>> listeners = Multimaps.newMultimap(new HashMap<>(),
				ArrayList::new);

		private boolean built = false;

		public Builder() {

		}

		/**
		 * Creates a builder that is a copy of the specified {@link MapLayer}
		 *
		 * @param layer
		 */
		public Builder(MapLayer layer) {
			name = layer.name;
			geometries.addAll(layer.geometries);
			color = layer.color;
			listeners.putAll(layer.listeners);
		}

		public MapLayer build() {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			validate();
			built = true;
			MapLayer layer = new MapLayer();
			layer.name = name;
			layer.geometries = geometries;
			layer.color = color;
			layer.listeners = listeners;
			layer.init();
			return layer;
		}

		private void validate() {
			if (name == null) {
				throw new IllegalStateException("The name cannot be null.");
			}
		}

		public Builder setName(String name) {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			if (name == null) {
				throw new IllegalArgumentException("The name cannot be null.");
			}
			this.name = name;
			return this;
		}

		public Builder addGeometry(GeometryWrapper geom) {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			if (geom == null) {
				throw new IllegalArgumentException("The geometry cannot be null.");
			}
			geometries.add(geom);
			return this;
		}

		public Builder removeGeometry(GeometryWrapper geom) {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			if (geom == null) {
				throw new IllegalArgumentException("The geometry cannot be null.");
			}
			geometries.remove(geom);
			return this;
		}

		public Builder setGeometries(List<GeometryWrapper> geometries) {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			if (geometries == null) {
				throw new IllegalArgumentException("The geometries cannot be null.");
			}
			this.geometries = new ArrayList<>(geometries);
			return this;
		}

		public Builder setColor(Color color) {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			if (color == null) {
				throw new IllegalArgumentException("The color cannot be null.");
			}
			this.color = color;
			return this;
		}

		public Builder addListener(MapLayerEventType type, Consumer<MapLayerEvent> listener) {
			return addListener(EnumSet.of(type), listener);
		}

		public Builder addListener(EnumSet<MapLayerEventType> types, Consumer<MapLayerEvent> listener) {
			if (built) {
				throw new IllegalStateException("The MapLayer has already been built.");
			}
			if (listener == null) {
				throw new IllegalArgumentException("The listener cannot be null.");
			}
			types.forEach(t -> listeners.put(t, listener));
			return this;
		}

	}

}