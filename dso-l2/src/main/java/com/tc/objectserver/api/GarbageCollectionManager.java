/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.api;

import com.tc.object.ObjectID;
import com.tc.objectserver.dgc.api.GarbageCollector.GCType;
import com.tc.util.ObjectIDSet;

import java.util.SortedSet;

public interface GarbageCollectionManager {

  public void startActiveMode();

  public void deleteObjects(SortedSet<ObjectID> objects);

  public ObjectIDSet nextObjectsToDelete();

  public void scheduleInlineGarbageCollectionIfNecessary();

  /**
   * Schedule a garbage collect to run asynchronously.
   */
  public void scheduleGarbageCollection(GCType type, long delay);

  /**
   * Run a garbage collect synchronously.
   */
  public void doGarbageCollection(GCType type);

  public void scheduleGarbageCollection(GCType type);
}
