/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.spatial4j.core.context;

import com.spatial4j.core.distance.CartesianDistCalc;
import com.spatial4j.core.distance.DistanceCalculator;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.distance.GeodesicSphereDistCalc;
import com.spatial4j.core.exception.InvalidShapeException;
import com.spatial4j.core.io.ShapeReadWriter;
import com.spatial4j.core.shape.Circle;
import com.spatial4j.core.shape.Point;
import com.spatial4j.core.shape.Rectangle;
import com.spatial4j.core.shape.Shape;
import com.spatial4j.core.shape.ShapeCollection;
import com.spatial4j.core.shape.impl.BufferedLineString;
import com.spatial4j.core.shape.impl.CircleImpl;
import com.spatial4j.core.shape.impl.GeoCircle;
import com.spatial4j.core.shape.impl.PointImpl;
import com.spatial4j.core.shape.impl.RectangleImpl;

import java.util.List;

/**
 * This is a facade to most of Spatial4j, holding things like {@link
 * DistanceCalculator}, and the coordinate world boundaries, and acting as a
 * factory for the {@link Shape}s.
 * <p/>
 * If you want a typical geodetic context, just reference {@link #GEO}.  Otherwise,
 * You should either create and configure a {@link SpatialContextFactory} and then call
 * {@link SpatialContextFactory#newSpatialContext()}, OR, call
 * {@link com.spatial4j.core.context.SpatialContextFactory#makeSpatialContext(java.util.Map, ClassLoader)}
 * to do this via configuration data.
 * <p/>
 * Thread-safe & immutable.
 */
public class SpatialContext {

  /** A popular default SpatialContext implementation for geospatial. */
  public static final SpatialContext GEO = new SpatialContext(true);

  //These are non-null
  private final boolean geo;
  private final DistanceCalculator calculator;
  private final Rectangle worldBounds;

  @Deprecated
  private final ShapeReadWriter shapeReadWriter;

  private final boolean normWrapLongitude;

  /**
   * Consider using {@link com.spatial4j.core.context.SpatialContextFactory} instead.
   *
   * @param geo Establishes geo vs cartesian / Euclidean.
   * @param calculator Optional; defaults to haversine or cartesian depending on {@code geo}.
   * @param worldBounds Optional; defaults to GEO_WORLDBOUNDS or MAX_WORLDBOUNDS depending on units.
   */
  @Deprecated
  public SpatialContext(boolean geo, DistanceCalculator calculator, Rectangle worldBounds) {
    this(initFromLegacyConstructor(geo, calculator, worldBounds));
  }

  private static SpatialContextFactory initFromLegacyConstructor(boolean geo,
                                                                 DistanceCalculator calculator,
                                                                 Rectangle worldBounds) {
    SpatialContextFactory factory = new SpatialContextFactory();
    factory.geo = geo;
    factory.distCalc = calculator;
    factory.worldBounds = worldBounds;
    return factory;
  }

  @Deprecated
  public SpatialContext(boolean geo) {
    this(geo, null, null);
  }

  /**
   * Called by {@link com.spatial4j.core.context.SpatialContextFactory#newSpatialContext()}.
   */
  public SpatialContext(SpatialContextFactory factory) {
    this.geo = factory.geo;

    if (factory.distCalc == null) {
      this.calculator = isGeo()
              ? new GeodesicSphereDistCalc.Haversine()
              : new CartesianDistCalc();
    } else {
      this.calculator = factory.distCalc;
    }

    //TODO remove worldBounds from Spatial4j: see Issue #55
    if (factory.worldBounds == null) {
      this.worldBounds = isGeo()
              ? new RectangleImpl(-180, 180, -90, 90, this)
              : new RectangleImpl(-Double.MAX_VALUE, Double.MAX_VALUE,
              -Double.MAX_VALUE, Double.MAX_VALUE, this);
    } else {
      if (isGeo())
        assert factory.worldBounds.equals(new RectangleImpl(-180, 180, -90, 90, this));
      if (factory.worldBounds.getCrossesDateLine())
        throw new IllegalArgumentException("worldBounds shouldn't cross dateline: "+factory.worldBounds);
      //hopefully worldBounds' rect implementation is compatible
      this.worldBounds = new RectangleImpl(factory.worldBounds, this);
    }

    this.shapeReadWriter = makeShapeReadWriter();
    this.normWrapLongitude = factory.normWrapLongitude && this.isGeo();
  }

  protected ShapeReadWriter makeShapeReadWriter() {
    return new ShapeReadWriter<SpatialContext>(this);
  }

  public DistanceCalculator getDistCalc() {
    return calculator;
  }

  /** Convenience that uses {@link #getDistCalc()} */
  public double calcDistance(Point p, double x2, double y2) {
    return getDistCalc().distance(p, x2, y2);
  }

  /** Convenience that uses {@link #getDistCalc()} */
  public double calcDistance(Point p, Point p2) {
    return getDistCalc().distance(p, p2);
  }

  /**
   * The extent of x & y coordinates should fit within the return'ed rectangle.
   * Do *NOT* invoke reset() on this return type.
   */
  public Rectangle getWorldBounds() {
    return worldBounds;
  }

  /** If true then {@link #normX(double)} will wrap longitudes outside of the standard
   * geodetic boundary into it. Example: 181 will become -179. */
  public boolean isNormWrapLongitude() {
    return normWrapLongitude;
  }

  /** Is this a geospatial context (true) or simply 2d spatial (false). */
  public boolean isGeo() {
    return geo;
  }

  /** Normalize the 'x' dimension. Might reduce precision or wrap it to be within the bounds. This
   * is called by {@link com.spatial4j.core.io.WktShapeParser} before creating a shape. */
  public double normX(double x) {
    if (normWrapLongitude)
      x = DistanceUtils.normLonDEG(x);
    return x;
  }

  /** Normalize the 'y' dimension. Might reduce precision or wrap it to be within the bounds. This
   * is called by {@link com.spatial4j.core.io.WktShapeParser} before creating a shape. */
  public double normY(double y) { return y; }

  /** Ensure fits in {@link #getWorldBounds()} */
  public void verifyX(double x) {
    Rectangle bounds = getWorldBounds();
    if (x < bounds.getMinX() || x > bounds.getMaxX())//NaN will pass
      throw new InvalidShapeException("Bad X value "+x+" is not in boundary "+bounds);
  }

  /** Ensure fits in {@link #getWorldBounds()} */
  public void verifyY(double y) {
    Rectangle bounds = getWorldBounds();
    if (y < bounds.getMinY() || y > bounds.getMaxY())//NaN will pass
      throw new InvalidShapeException("Bad Y value "+y+" is not in boundary "+bounds);
  }

  /** Construct a point. */
  public Point makePoint(double x, double y) {
    verifyX(x);
    verifyY(y);
    return new PointImpl(x, y, this);
  }

  /** Construct a rectangle. */
  public Rectangle makeRectangle(Point lowerLeft, Point upperRight) {
    return makeRectangle(lowerLeft.getX(), upperRight.getX(),
            lowerLeft.getY(), upperRight.getY());
  }

  /**
   * Construct a rectangle. If just one longitude is on the dateline (+/- 180)
   * then potentially adjust its sign to ensure the rectangle does not cross the
   * dateline.
   */
  public Rectangle makeRectangle(double minX, double maxX, double minY, double maxY) {
    Rectangle bounds = getWorldBounds();
    // Y
    if (minY < bounds.getMinY() || maxY > bounds.getMaxY())//NaN will pass
      throw new InvalidShapeException("Y values ["+minY+" to "+maxY+"] not in boundary "+bounds);
    if (minY > maxY)
      throw new InvalidShapeException("maxY must be >= minY: " + minY + " to " + maxY);
    // X
    if (isGeo()) {
      verifyX(minX);
      verifyX(maxX);
      //TODO consider removing this logic so that there is no normalization here
      //if (minX != maxX) {   USUALLY TRUE, inline check below
      //If an edge coincides with the dateline then don't make this rect cross it
      if (minX == 180 && minX != maxX) {
        minX = -180;
      } else if (maxX == -180 && minX != maxX) {
        maxX = 180;
      }
      //}
    } else {
      if (minX < bounds.getMinX() || maxX > bounds.getMaxX())//NaN will pass
        throw new InvalidShapeException("X values ["+minX+" to "+maxX+"] not in boundary "+bounds);
      if (minX > maxX)
        throw new InvalidShapeException("maxX must be >= minX: " + minX + " to " + maxX);
    }
    return new RectangleImpl(minX, maxX, minY, maxY, this);
  }

  /** Construct a circle. The units of "distance" should be the same as x & y. */
  public Circle makeCircle(double x, double y, double distance) {
    return makeCircle(makePoint(x, y), distance);
  }

  /** Construct a circle. The units of "distance" should be the same as x & y. */
  public Circle makeCircle(Point point, double distance) {
    if (distance < 0)
      throw new InvalidShapeException("distance must be >= 0; got " + distance);
    if (isGeo()) {
      if (distance > 180) {
        // (it's debatable whether to error or not)
        //throw new InvalidShapeException("distance must be <= 180; got " + distance);
        distance = 180;
      }
      return new GeoCircle(point, distance, this);
    } else {
      return new CircleImpl(point, distance, this);
    }
  }

  /** Constructs a line string. It's an ordered sequence of connected vertexes. There
   * is no official shape/interface for it so we just return Shape. */
  public Shape makeLineString(List<Point> points) {
    //no "proper" LineString interface yet so we declare to return Shape
    return new BufferedLineString(points, 0, false, this);
  }

  /** Constructs a buffered line string. It's an ordered sequence of connected vertexes,
   * with a buffer distance along the line in all directions. There
   * is no official shape/interface for it so we just return Shape. */
  public Shape makeBufferedLineString(List<Point> points, double buf) {
    return new BufferedLineString(points, buf, isGeo(), this);
  }

  /** Construct a ShapeCollection, analogous to an OGC GeometryCollection. */
  public <S extends Shape> ShapeCollection<S> makeCollection(List<S> coll) {
    return new ShapeCollection<S>(coll, this);
  }

  @Deprecated
  public Shape readShape(String value) throws InvalidShapeException {
    return shapeReadWriter.readShape(value);
  }

  @Deprecated
  public String toString(Shape shape) {
    return shapeReadWriter.writeShape(shape);
  }

  @Override
  public String toString() {
    if (this.equals(GEO)) {
      return GEO.getClass().getSimpleName()+".GEO";
    } else {
      return getClass().getSimpleName()+"{" +
          "geo=" + geo +
          ", calculator=" + calculator +
          ", worldBounds=" + worldBounds +
          '}';
    }
  }

}
