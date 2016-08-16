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

package org.apache.zeppelin.interpreter;

import org.apache.log4j.Logger;
import org.apache.zeppelin.display.AngularObjectRegistry;
import org.apache.zeppelin.interpreter.remote.RemoteInterpreterProcess;
import org.apache.zeppelin.resource.ResourcePool;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InterpreterGroup is list of interpreters in the same interpreter group.
 * For example spark, pyspark, sql interpreters are in the same 'spark' group
 * and InterpreterGroup will have reference to these all interpreters.
 * <p>
 * Remember, list of interpreters are dedicated to a note.
 * (when InterpreterOption.perNoteSession==true)
 * So InterpreterGroup internally manages map of [noteId, list of interpreters]
 * <p>
 * A InterpreterGroup runs on interpreter process.
 * And unit of interpreter instantiate, restart, bind, unbind.
 */
public class InterpreterGroup extends ConcurrentHashMap<String, List<Interpreter>> {
  String id;

  Logger LOGGER = Logger.getLogger(InterpreterGroup.class);

  AngularObjectRegistry angularObjectRegistry;
  RemoteInterpreterProcess remoteInterpreterProcess;    // attached remote interpreter process
  ResourcePool resourcePool;
  boolean angularRegistryPushed = false;

  // map [notebook session, Interpreters in the group], to support per note session interpreters
  //Map<String, List<Interpreter>> interpreters = new ConcurrentHashMap<String,
  // List<Interpreter>>();

  private static final Map<String, InterpreterGroup> allInterpreterGroups =
      new ConcurrentHashMap<String, InterpreterGroup>();

  public static InterpreterGroup getByInterpreterGroupId(String id) {
    return allInterpreterGroups.get(id);
  }

  public static Collection<InterpreterGroup> getAll() {
    return new LinkedList(allInterpreterGroups.values());
  }

  /**
   * Create InterpreterGroup with given id
   *
   * @param id
   */
  public InterpreterGroup(String id) {
    this.id = id;
    allInterpreterGroups.put(id, this);
  }

  /**
   * Create InterpreterGroup with autogenerated id
   */
  public InterpreterGroup() {
    getId();
    allInterpreterGroups.put(id, this);
  }

  private static String generateId() {
    return "InterpreterGroup_" + System.currentTimeMillis() + "_"
        + new Random().nextInt();
  }

  public String getId() {
    synchronized (this) {
      if (id == null) {
        id = generateId();
      }
      return id;
    }
  }

  /**
   * Get combined property of all interpreters in this group
   *
   * @return
   */
  public Properties getProperty() {
    Properties p = new Properties();

    Collection<List<Interpreter>> intpGroupForANote = this.values();
    if (intpGroupForANote != null && intpGroupForANote.size() > 0) {
      for (List<Interpreter> intpGroup : intpGroupForANote) {
        for (Interpreter intp : intpGroup) {
          p.putAll(intp.getProperty());
        }
        // it's okay to break here while every List<Interpreters> will have the same property set
        break;
      }
    }
    return p;
  }

  public AngularObjectRegistry getAngularObjectRegistry() {
    return angularObjectRegistry;
  }

  public void setAngularObjectRegistry(AngularObjectRegistry angularObjectRegistry) {
    this.angularObjectRegistry = angularObjectRegistry;
  }

  public RemoteInterpreterProcess getRemoteInterpreterProcess() {
    return remoteInterpreterProcess;
  }

  public void setRemoteInterpreterProcess(RemoteInterpreterProcess remoteInterpreterProcess) {
    this.remoteInterpreterProcess = remoteInterpreterProcess;
  }

  /**
   * Close all interpreter instances in this group
   */
  public void close() {
    LOGGER.info("Close interpreter group " + getId());
    List<Interpreter> intpToClose = new LinkedList<Interpreter>();
    for (List<Interpreter> intpGroupForNote : this.values()) {
      intpToClose.addAll(intpGroupForNote);
    }
    close(intpToClose);
  }

  /**
   * Close all interpreter instances in this group for the note
   *
   * @param noteId
   */
  public void close(String noteId) {
    LOGGER.info("Close interpreter group " + getId() + " for note " + noteId);
    List<Interpreter> intpForNote = this.get(noteId);
    close(intpForNote);
  }

  private void close(Collection<Interpreter> intpToClose) {
    if (intpToClose == null) {
      return;
    }
    List<Thread> closeThreads = new LinkedList<Thread>();

    for (final Interpreter intp : intpToClose) {
      Thread t = new Thread() {
        public void run() {
          Scheduler scheduler = intp.getScheduler();
          intp.close();

          if (scheduler != null) {
            SchedulerFactory.singleton().removeScheduler(scheduler.getName());
          }
        }
      };

      t.start();
      closeThreads.add(t);
    }

    for (Thread t : closeThreads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        LOGGER.error("Can't close interpreter", e);
      }
    }
  }

  /**
   * Destroy all interpreter instances in this group for the note
   *
   * @param noteId
   */
  public void destroy(String noteId) {
    LOGGER.info("Destroy interpreter group " + getId() + " for note " + noteId);
    List<Interpreter> intpForNote = this.get(noteId);
    destroy(intpForNote);
  }


  /**
   * Destroy all interpreter instances in this group
   */
  public void destroy() {
    LOGGER.info("Destroy interpreter group " + getId());
    List<Interpreter> intpToDestroy = new LinkedList<Interpreter>();
    for (List<Interpreter> intpGroupForNote : this.values()) {
      intpToDestroy.addAll(intpGroupForNote);
    }
    destroy(intpToDestroy);

    // make sure remote interpreter process terminates
    if (remoteInterpreterProcess != null) {
      while (remoteInterpreterProcess.referenceCount() > 0) {
        remoteInterpreterProcess.dereference();
      }
    }


    allInterpreterGroups.remove(id);
  }

  private void destroy(Collection<Interpreter> intpToDestroy) {
    if (intpToDestroy == null) {
      return;
    }

    List<Thread> destroyThreads = new LinkedList<Thread>();

    for (final Interpreter intp : intpToDestroy) {
      Thread t = new Thread() {
        public void run() {
          intp.destroy();
        }
      };

      t.start();
      destroyThreads.add(t);
    }

    for (Thread t : destroyThreads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        LOGGER.error("Can't close interpreter", e);
      }
    }
  }


  public void setResourcePool(ResourcePool resourcePool) {
    this.resourcePool = resourcePool;
  }

  public ResourcePool getResourcePool() {
    return resourcePool;
  }

  public boolean isAngularRegistryPushed() {
    return angularRegistryPushed;
  }

  public void setAngularRegistryPushed(boolean angularRegistryPushed) {
    this.angularRegistryPushed = angularRegistryPushed;
  }
}
