/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.objectserver.handler;

import com.tc.objectserver.api.Retiree;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.terracotta.entity.ConcurrencyStrategy;
import org.terracotta.entity.EntityMessage;

import com.tc.objectserver.api.ServerEntityResponse;
import com.tc.util.Assert;
import java.util.ArrayList;


/**
 * The ability to defer retirement introduces a complex dependency graph (tree) between the messages in the system.
 * While a message can wait for another to complete, before retiring (a "defer" operation), the messages within a given
 * concurrency key also represent a logical ordering which must be preserved.
 * This means that each message retirement can branch in 2 ways:
 *  1) The next message in the same key
 *  2) Any message which had deferred to it
 * The side-effect of these 2 statements is that it is possible for a single message completion to result in the
 * retirement of a great number of other messages, as each message unblocked can similarly unblock 2 more.
 * 
 * NOTE:  It may be possible to avoid the synchronization on most operations since we know that there is only one running
 * message, per key.
 */
public class RetirementManager {
  private final Map<EntityMessage, LogicalSequence> currentlyRunning;
  private final Map<EntityMessage, LogicalSequence> waitingForDeferredRegistration;
  private final Map<Integer, LogicalSequence> mostRecentRegisteredToKey;


  public RetirementManager() {
    this.currentlyRunning = new HashMap<EntityMessage, LogicalSequence>();
    this.waitingForDeferredRegistration = new HashMap<EntityMessage, LogicalSequence>();
    this.mostRecentRegisteredToKey = new HashMap<Integer, LogicalSequence>();
  }
  
  public synchronized void updateWithRetiree(EntityMessage invokeMessage, Retiree response) {
    this.currentlyRunning.get(invokeMessage).updateWithRetiree(response);
  }

  public synchronized void registerWithMessage(EntityMessage invokeMessage, int concurrencyKey) {    
    LogicalSequence newWrapper = new LogicalSequence(invokeMessage);
    // if concurrencyKey is UNIVERSAL_KEY, then current request doesn't need to wait for other requests running on
    // UNIVERSAL_KEY
    if(concurrencyKey != ConcurrencyStrategy.UNIVERSAL_KEY) {
      // See if there is anything for this key
      LogicalSequence lastInKey = this.mostRecentRegisteredToKey.remove(concurrencyKey);
      if ((null != lastInKey) && (!lastInKey.isRetired)) {
        lastInKey.nextInKey = newWrapper;
        newWrapper.isWaitingForPreviousInKey = true;
      }
      this.mostRecentRegisteredToKey.put(concurrencyKey, newWrapper);
    }
    
    LogicalSequence toUpdateWithReference = waitingForDeferredRegistration.remove(invokeMessage);
    if (null != toUpdateWithReference) {
      Assert.assertTrue(toUpdateWithReference.isWaitingForExplicitDeferOf(invokeMessage));
      newWrapper.deferNotify = toUpdateWithReference;
    }
    
    LogicalSequence previous = this.currentlyRunning.put(invokeMessage, newWrapper);
    // We can't find something else there.
    Assert.assertNull(previous);
  }

  /**
   * This returns a list because it is possible to return a sequence of queued up retirements:  completedMessage may unblock
   * an earlier retirement which is followed by a logical sequence of operations which couldn't retire until it did.
   * 
   * @param completedMessage
   * @return
   */
  public synchronized List<Retiree> retireForCompletion(EntityMessage completedMessage) {
    List<Retiree> toRetire = new ArrayList<>();
    
    LogicalSequence completedRequest = this.currentlyRunning.remove(completedMessage);
    Assert.assertNotNull(completedRequest);

    Assert.assertFalse(completedRequest.isCompleted);
    completedRequest.isCompleted = true;
    traverseDependencyGraph(toRetire, completedRequest);
    return toRetire;
  }

  private void traverseDependencyGraph(List<Retiree> toRetire, LogicalSequence completedRequest) {
    Stack<LogicalSequence> requestStack = new Stack();
    requestStack.add(completedRequest);

    while(!requestStack.isEmpty()) {
      LogicalSequence currentRequest = requestStack.pop();
      Assert.assertFalse(currentRequest.isRetired);

      // proceed if current request is completed
      if(currentRequest.isCompleted) {
        // See if we are still waiting for anyone.
        if (!currentRequest.isWaitingForExplicitDefer() && !currentRequest.isWaitingForPreviousInKey) {
          // We can retire.
          toRetire.add(currentRequest.response);
          currentRequest.isRetired = true;

          // since current request is retired, we can unblock next request on same concurrency key if any
          if (currentRequest.nextInKey != null) {
            currentRequest.nextInKey.isWaitingForPreviousInKey = false;
            requestStack.push(currentRequest.nextInKey);
            currentRequest.nextInKey = null;
          }
        }

        // since current request is completed, we can unblock any request waiting on this request if any
        if (currentRequest.deferNotify != null) {
          currentRequest.deferNotify.entityMessageCompleted(currentRequest.entityMessage);
          requestStack.push(currentRequest.deferNotify);
          currentRequest.deferNotify = null;
        }
      }
    }
  }

  public synchronized void deferRetirement(EntityMessage invokeMessageToDefer, EntityMessage laterMessage) {
    LogicalSequence myRequest = this.currentlyRunning.get(invokeMessageToDefer);
    // We can only defer by currently running messages.
    Assert.assertNotNull(myRequest);
    
    myRequest.retirementDeferredBy(laterMessage);
    
    LogicalSequence previous = this.waitingForDeferredRegistration.put(laterMessage, myRequest);
    Assert.assertNull(previous);
  }


  private static class LogicalSequence {
    // The thing to be retired
    public Retiree response;
    // Corresponding entity message
    public final EntityMessage entityMessage;
    // The next message in the same key, which we will notify to retire when we retire.
    public LogicalSequence nextInKey;
    // The message which is explicitly waiting for us to retire before it can.
    public LogicalSequence deferNotify;
    // True if we are still waiting for the previous in our key to retire.
    public boolean isWaitingForPreviousInKey;
    // True if the request is completed
    public boolean isCompleted;
    // True if retirement is complete (only used when stitching in the key).
    public boolean isRetired;

    private Set<EntityMessage> entityMessagesDeferringRetirement = new HashSet<>();
    
    public LogicalSequence(EntityMessage entityMessage) {
      this.entityMessage = entityMessage;
    }
    
    public void updateWithRetiree(Retiree response) {
      this.response = response;
    }

    public void retirementDeferredBy(EntityMessage entityMessage) {
      // just add this entityMessage to waiting set
      entityMessagesDeferringRetirement.add(entityMessage);
    }

    public void entityMessageCompleted(EntityMessage entityMessage) {
      // remove entityMessage from waiting set and return status for asserting
      entityMessagesDeferringRetirement.remove(entityMessage);
    }

    public boolean isWaitingForExplicitDefer() {
      // true if waiting set size is not zero
      return entityMessagesDeferringRetirement.size() != 0;
    }

    public boolean isWaitingForExplicitDeferOf(EntityMessage entityMessage) {
      return entityMessagesDeferringRetirement.contains(entityMessage);
    }
  }
}
