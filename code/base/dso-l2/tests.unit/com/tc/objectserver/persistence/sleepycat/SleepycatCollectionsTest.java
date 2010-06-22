/*
 * All content copyright (c) 20032008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.
 * All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.ObjectID;
import com.tc.object.TestDNACursor;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.managedobject.ManagedObjectStateFactory;
import com.tc.objectserver.managedobject.MapManagedObjectState;
import com.tc.objectserver.managedobject.NullManagedObjectChangeListenerProvider;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;
import com.tc.objectserver.persistence.api.PersistentCollectionFactory;
import com.tc.test.TCTestCase;
import com.tc.util.Assert;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class SleepycatCollectionsTest extends TCTestCase {

  private SleepycatPersistor             persistor;
  private PersistenceTransactionProvider ptp;
  private DBEnvironment                  env;
  private PersistentCollectionFactory    collectionsFactory;
  private SleepycatCollectionsPersistor  collectionsPersistor;
  private static int                     dbHomeCounter = 0;
  private static File                    tempDirectory;

  @Override
  public void setUp() throws Exception {

    if (this.env != null) {
      this.env.close();
    }
    final File dbHome = newDBHome();
    final TCLogger logger = TCLogging.getLogger(getClass());
    final CustomSerializationAdapterFactory saf = new CustomSerializationAdapterFactory();
    this.env = new DBEnvironment(true, dbHome);
    this.persistor = new SleepycatPersistor(logger, this.env, saf);
    this.ptp = this.persistor.getPersistenceTransactionProvider();
    this.collectionsFactory = this.persistor.getPersistentCollectionFactory();
    this.collectionsPersistor = this.persistor.getCollectionsPersistor();
  }

  // XXX:: Check SleepycatSerializationTest if you want know why its done like this or ask Orion.
  private File newDBHome() throws IOException {
    File file;
    if (tempDirectory == null) {
      tempDirectory = getTempDirectory();
    }
    ++dbHomeCounter;
    for (file = new File(tempDirectory, "db" + dbHomeCounter); file.exists(); ++dbHomeCounter) {
      //
    }
    assertFalse(file.exists());
    System.err.println("DB Home = " + file);
    return file;
  }

  @Override
  public void tearDown() throws Exception {
    this.persistor = null;
    this.ptp = null;
    this.env = null;
  }

  public void testSleepycatPersistableMap() throws Exception {
    ManagedObjectStateFactory.createInstance(new NullManagedObjectChangeListenerProvider(), this.persistor);
    final ObjectID id = new ObjectID(1);
    final MapManagedObjectState state1 = (MapManagedObjectState) ManagedObjectStateFactory.getInstance()
        .createState(id, ObjectID.NULL_ID, "java.util.HashMap", "System.loader", new TestDNACursor());
    final SleepycatPersistableMap sMap = (SleepycatPersistableMap) state1.getPersistentCollection();
    addToMap(sMap);
    final Map localMap = new HashMap();
    addToMap(localMap);
    equals(localMap, sMap);

    PersistenceTransaction tx = this.ptp.newTransaction();
    this.collectionsPersistor.saveCollections(tx, state1);
    tx.commit();
    equals(localMap, sMap);

    tx = this.ptp.newTransaction();
    final MapManagedObjectState state2 = (MapManagedObjectState) ManagedObjectStateFactory.getInstance()
        .createState(new ObjectID(2), ObjectID.NULL_ID, "java.util.HashMap", "System.loader", new TestDNACursor());
    SleepycatPersistableMap sMap2 = (SleepycatPersistableMap) state2.getPersistentCollection();
    tx.commit();
    equals(new HashMap(), sMap2);

    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(localMap, sMap2);

    System.err.println(" Adding more maps ....");
    addMoreMaps(state1);

    System.err.println(" Loading map again ....");
    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(localMap, sMap2);

    System.err.println(" Loading different map ....");
    tx = this.ptp.newTransaction();
    final SleepycatPersistableMap sMap3 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(localMap, sMap3);

    addToMap(sMap, 2);
    addToMap(localMap, 2);
    equals(localMap, sMap);

    tx = this.ptp.newTransaction();
    this.collectionsPersistor.saveCollections(tx, state1);
    tx.commit();
    equals(localMap, sMap);

    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(localMap, sMap2);

    addAndRemoveFromMap(sMap);
    addAndRemoveFromMap(localMap);
    Assert.assertEquals(localMap, sMap);

    tx = this.ptp.newTransaction();
    this.collectionsPersistor.saveCollections(tx, state1);
    tx.commit();
    equals(localMap, sMap);

    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(localMap, sMap2);

    addRemoveClearFromMap(sMap);
    addRemoveClearFromMap(localMap);
    equals(localMap, sMap);

    tx = this.ptp.newTransaction();
    this.collectionsPersistor.saveCollections(tx, state1);
    tx.commit();
    equals(localMap, sMap);

    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(localMap, sMap2);

    tx = this.ptp.newTransaction();
    SortedSet<ObjectID> idsToDelete = new TreeSet<ObjectID>();
    idsToDelete.add(id);
    Assert.assertEquals(40, this.collectionsPersistor.deleteAllCollections(ptp, idsToDelete));
    tx.commit();

    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    sMap2.clear();
    tx.commit();

    tx = this.ptp.newTransaction();
    sMap2 = (SleepycatPersistableMap) state1.getPersistentCollection();
    tx.commit();
    equals(new HashMap(), sMap2);

    tx = this.ptp.newTransaction();
    Assert.assertEquals(0, this.collectionsPersistor.deleteAllCollections(ptp, idsToDelete));
    tx.commit();

  }

  private void equals(final Map m1, final Map m2) {
    Assert.assertEquals(m1.size(), m2.size());
    Assert.assertEquals(m1, m2);
    equals(m1.keySet(), m2.keySet());
    equals(m1.values(), m2.values());
    Assert.assertEquals(m1.entrySet(), m2.entrySet());
  }

  // This implementation does not care about the order
  private void equals(final Collection c1, final Collection c2) {
    Assert.assertEquals(c1.size(), c2.size());
    Assert.assertTrue(c1.containsAll(c2));
    Assert.assertTrue(c2.containsAll(c1));
  }

  private void equals(final Set s1, final Set s2) {
    Assert.assertEquals(s1, s2);
    equals(Arrays.asList(s1.toArray()), Arrays.asList(s2.toArray()));
  }

  private void addMoreMaps(final ManagedObjectState state) throws IOException, TCDatabaseException {
    for (int j = 20; j < 40; j++) {
      final ObjectID id = new ObjectID(j);
      final SleepycatPersistableMap sMap = (SleepycatPersistableMap) this.collectionsFactory.createPersistentMap(id);
      addToMap(sMap);
      final PersistenceTransaction tx = this.ptp.newTransaction();
      this.collectionsPersistor.saveCollections(tx, state);
      tx.commit();
    }
  }

  private void addToMap(final Map map) {
    addToMap(map, 1);
  }

  private void addToMap(final Map map, final int increCount) {
    int j = 0;
    for (int i = 0; i < 50; i++, j += increCount) {
      map.put(new ObjectID(j), new ObjectID(100 + j));
      map.put(new Integer(j), new Long(j));
      map.put(new String("" + j), new String("" + j));
      map.put(new Double(j + 0.005), new Float(j - 0.004));
    }
  }

  private void addAndRemoveFromMap(final Map map) {
    int j = 50;
    for (int i = 0; i < 50; i++, j++) {
      map.put(new ObjectID(j), new ObjectID(100 + j));
      map.put(new Integer(j), new Long(j));
      map.put(new String("" + j), new String("" + j));
      map.put(new Double(j + 0.005), new Float(j - 0.004));
      map.remove(new ObjectID(j - 25));
      map.remove(new Integer(j - 25));
      map.remove(new String("" + (j - 25)));
      map.remove(new Double((j - 25) + 0.005));
    }
  }

  private void addRemoveClearFromMap(final Map map) {
    int j = 100;
    for (int i = 0; i < 50; i++, j++) {
      map.put(new ObjectID(j), new ObjectID(100 + j));
      map.put(new Integer(j), new Long(j));
      map.put(new String("" + j), new String("" + j));
      map.put(new Double(j + 0.005), new Float(j - 0.004));
      map.remove(new ObjectID(j - 25));
      map.remove(new Integer(j - 25));
      map.remove(new String("" + (j - 25)));
      map.remove(new Double((j - 25) + 0.005));
      if (i % 20 == 19) {
        map.clear();
      }
    }
  }
}
