/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.spatial.strategy;


import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import junit.framework.Assert;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.spatial.base.context.SpatialContext;
import org.apache.lucene.spatial.base.io.sample.SampleData;
import org.apache.lucene.spatial.base.io.sample.SampleDataReader;
import org.apache.lucene.spatial.base.query.SpatialArgsParser;
import org.apache.lucene.spatial.base.shape.Shape;


public abstract class StrategyTestCase<T extends SpatialFieldInfo> extends SpatialTestCase {

  public static final String DATA_STATES_POLY = "states-poly.txt";
  public static final String DATA_STATES_BBOX = "states-bbox.txt";
  public static final String DATA_COUNTRIES_POLY = "countries-poly.txt";
  public static final String DATA_COUNTRIES_BBOX = "countries-bbox.txt";
  public static final String DATA_WORLD_CITIES_POINTS = "world-cities-points.txt";

  public static final String QTEST_States_IsWithin_BBox   = "states-IsWithin-BBox.txt";
  public static final String QTEST_States_Intersects_BBox = "states-Intersects-BBox.txt";

  public static final String QTEST_Cities_IsWithin_BBox = "cities-IsWithin-BBox.txt";


  protected final SpatialArgsParser argsParser = new SpatialArgsParser();

  protected void executeQueries(
      SpatialStrategy<T> strategy,
      SpatialContext shapeIO,
      T fieldInfo,
      SpatialMatchConcerns concern,
      String testDataFile,
      String ... testQueryFile ) throws IOException {
    List<Document> testDocuments = getDocuments(testDataFile, shapeIO, strategy, fieldInfo);
    addDocuments(testDocuments);
    verifyDocumentsIndexed(testDocuments.size());

    for( String path : testQueryFile ) {
      Iterator<SpatialTestQuery> testQueryIterator = getTestQueries(path, shapeIO);
      runTestQueries(testQueryIterator, shapeIO, concern, strategy, fieldInfo);
    }
  }

  protected List<Document> getDocuments(String testDataFile, SpatialContext shapeIO, SpatialStrategy<T> strategy, T fieldInfo) throws IOException {
    Iterator<SampleData> sampleData = getSampleData(testDataFile);
    List<Document> documents = new ArrayList<Document>();
    while (sampleData.hasNext()) {
      SampleData data = sampleData.next();
      Document document = new Document();
      document.add(new Field("id", data.id, Store.YES, Index.ANALYZED));
      document.add(new Field("name", data.name, Store.YES, Index.ANALYZED));
      Shape shape = shapeIO.readShape(data.shape);
      for (Fieldable f : strategy.createFields(fieldInfo, shape, true, true)) {
        if( f != null ) { // null if incompatibleGeometry && ignore
          document.add(f);
        }
      }
      documents.add(document);
    }
    return documents;
  }

  protected Iterator<SampleData> getSampleData(String testDataFile) throws IOException {
    return new SampleDataReader(
        getClass().getClassLoader().getResourceAsStream("data/"+testDataFile) );
  }

  protected Iterator<SpatialTestQuery> getTestQueries(String testQueryFile, SpatialContext shapeIO) throws IOException {
    InputStream in = getClass().getClassLoader().getResourceAsStream(testQueryFile);
    return SpatialTestQuery.getTestQueries(
        argsParser, shapeIO, testQueryFile, in );
  }

  public void runTestQueries(
      Iterator<SpatialTestQuery> queries,
      SpatialContext shapeIO,
      SpatialMatchConcerns concern,
      SpatialStrategy<T> strategy,
      T fieldInfo) {
    while (queries.hasNext()) {
      SpatialTestQuery q = queries.next();

      String msg = q.line; //"Query: " + q.args.toString(shapeIO);
      SearchResults got = executeQuery(strategy.makeQuery(q.args, fieldInfo), 100);
      if (concern.orderIsImportant) {
        Iterator<String> ids = q.ids.iterator();
        for (SearchResult r : got.results) {
          String id = r.document.get("id");
          Assert.assertEquals( "out of order: " + msg, ids.next(), id);
        }
        if (ids.hasNext()) {
          Assert.fail(msg + " :: expect more results then we got: " + ids.next());
        }
      } else {
        // We are looking at how the results overlap
        if( concern.resultsAreSuperset ) {
          Set<String> found = new HashSet<String>();
          for (SearchResult r : got.results) {
            found.add(r.document.get("id"));
          }
          for( String s : q.ids ) {
            if( !found.contains( s ) ) {
              Assert.fail( "Results are mising id: "+s + " :: " + found );
            }
          }
        }
        else {
          List<String> found = new ArrayList<String>();
          for (SearchResult r : got.results) {
            found.add(r.document.get("id"));
          }

          // sort both so that the order is not important
          Collections.sort(q.ids);
          Collections.sort(found);
          Assert.assertEquals(msg, q.ids.toString(), found.toString());
        }
      }
    }
  }
}
