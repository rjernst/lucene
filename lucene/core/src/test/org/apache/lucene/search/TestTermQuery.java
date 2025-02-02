/*
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
package org.apache.lucene.search;

import java.io.IOException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.CompositeReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;

public class TestTermQuery extends LuceneTestCase {

  public void testEquals() throws IOException {
    QueryUtils.checkEqual(
        new TermQuery(new Term("foo", "bar")), new TermQuery(new Term("foo", "bar")));
    QueryUtils.checkUnequal(
        new TermQuery(new Term("foo", "bar")), new TermQuery(new Term("foo", "baz")));
    final CompositeReaderContext context;
    try (MultiReader multiReader = new MultiReader()) {
      context = multiReader.getContext();
    }
    QueryUtils.checkEqual(
        new TermQuery(new Term("foo", "bar")),
        new TermQuery(
            new Term("foo", "bar"), TermStates.build(context, new Term("foo", "bar"), true)));
  }

  public void testCreateWeightDoesNotSeekIfScoresAreNotNeeded() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w =
        new RandomIndexWriter(
            random(), dir, newIndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE));
    // segment that contains the term
    Document doc = new Document();
    doc.add(new StringField("foo", "bar", Store.NO));
    w.addDocument(doc);
    w.getReader().close();
    // segment that does not contain the term
    doc = new Document();
    doc.add(new StringField("foo", "baz", Store.NO));
    w.addDocument(doc);
    w.getReader().close();
    // segment that does not contain the field
    w.addDocument(new Document());

    DirectoryReader reader = w.getReader();
    FilterDirectoryReader noSeekReader = new NoSeekDirectoryReader(reader);
    IndexSearcher noSeekSearcher = new IndexSearcher(noSeekReader);
    Query query = new TermQuery(new Term("foo", "bar"));
    AssertionError e =
        expectThrows(
            AssertionError.class,
            () ->
                noSeekSearcher.createWeight(noSeekSearcher.rewrite(query), ScoreMode.COMPLETE, 1));
    assertEquals("no seek", e.getMessage());

    noSeekSearcher.createWeight(
        noSeekSearcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1); // no exception
    IndexSearcher searcher = new IndexSearcher(reader);
    // use a collector rather than searcher.count() which would just read the
    // doc freq instead of creating a scorer
    TotalHitCountCollector collector = new TotalHitCountCollector();
    searcher.search(query, collector);
    assertEquals(1, collector.getTotalHits());
    TermQuery queryWithContext =
        new TermQuery(
            new Term("foo", "bar"),
            TermStates.build(reader.getContext(), new Term("foo", "bar"), true));
    collector = new TotalHitCountCollector();
    searcher.search(queryWithContext, collector);
    assertEquals(1, collector.getTotalHits());

    IOUtils.close(reader, w, dir);
  }

  // LUCENE-9620 Add Weight#count(LeafReaderContext)
  public void testQueryMatchesCount() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);

    int randomNumDocs = random().nextInt(500);
    int numMatchingDocs = 0;

    for (int i = 0; i < randomNumDocs; i++) {
      Document doc = new Document();
      if (random().nextBoolean()) {
        doc.add(new StringField("foo", "bar", Store.NO));
        numMatchingDocs++;
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);

    DirectoryReader reader = w.getReader();
    final IndexSearcher searcher = new IndexSearcher(reader);

    Query testQuery = new TermQuery(new Term("foo", "bar"));
    assertEquals(searcher.count(testQuery), numMatchingDocs);
    final Weight weight = searcher.createWeight(testQuery, ScoreMode.COMPLETE, 1);
    assertEquals(weight.count(reader.leaves().get(0)), numMatchingDocs);

    IOUtils.close(reader, w, dir);
  }

  public void testGetTermStates() throws Exception {

    // no term states:
    assertNull(new TermQuery(new Term("foo", "bar")).getTermStates());

    Directory dir = newDirectory();
    RandomIndexWriter w =
        new RandomIndexWriter(
            random(), dir, newIndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE));
    // segment that contains the term
    Document doc = new Document();
    doc.add(new StringField("foo", "bar", Store.NO));
    w.addDocument(doc);
    w.getReader().close();
    // segment that does not contain the term
    doc = new Document();
    doc.add(new StringField("foo", "baz", Store.NO));
    w.addDocument(doc);
    w.getReader().close();
    // segment that does not contain the field
    w.addDocument(new Document());

    DirectoryReader reader = w.getReader();
    TermQuery queryWithContext =
        new TermQuery(
            new Term("foo", "bar"),
            TermStates.build(reader.getContext(), new Term("foo", "bar"), true));
    assertNotNull(queryWithContext.getTermStates());
    IOUtils.close(reader, w, dir);
  }

  private static class NoSeekDirectoryReader extends FilterDirectoryReader {

    public NoSeekDirectoryReader(DirectoryReader in) throws IOException {
      super(
          in,
          new SubReaderWrapper() {
            @Override
            public LeafReader wrap(LeafReader reader) {
              return new NoSeekLeafReader(reader);
            }
          });
    }

    @Override
    protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
      return new NoSeekDirectoryReader(in);
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
      return in.getReaderCacheHelper();
    }
  }

  private static class NoSeekLeafReader extends FilterLeafReader {

    public NoSeekLeafReader(LeafReader in) {
      super(in);
    }

    @Override
    public Terms terms(String field) throws IOException {
      Terms terms = super.terms(field);
      return terms == null
          ? null
          : new FilterTerms(terms) {
            @Override
            public TermsEnum iterator() throws IOException {
              return new FilterTermsEnum(super.iterator()) {
                @Override
                public SeekStatus seekCeil(BytesRef text) throws IOException {
                  throw new AssertionError("no seek");
                }

                @Override
                public void seekExact(BytesRef term, TermState state) throws IOException {
                  throw new AssertionError("no seek");
                }

                @Override
                public boolean seekExact(BytesRef text) throws IOException {
                  throw new AssertionError("no seek");
                }

                @Override
                public void seekExact(long ord) throws IOException {
                  throw new AssertionError("no seek");
                }
              };
            }
          };
    }

    @Override
    public CacheHelper getCoreCacheHelper() {
      return in.getCoreCacheHelper();
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
      return in.getReaderCacheHelper();
    }
  }
  ;
}
