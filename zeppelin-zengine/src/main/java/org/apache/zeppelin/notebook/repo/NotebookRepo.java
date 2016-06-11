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

package org.apache.zeppelin.notebook.repo;

import java.io.IOException;
import java.util.List;

import org.apache.zeppelin.annotation.ZeppelinApi;
import org.apache.zeppelin.notebook.Note;
import org.apache.zeppelin.notebook.NoteInfo;
import org.apache.zeppelin.user.AuthenticationInfo;

/**
 * Notebook repository (persistence layer) abstraction
 */
public interface NotebookRepo {
  @ZeppelinApi public List<NoteInfo> list(AuthenticationInfo subject) throws IOException;
  @ZeppelinApi public Note get(String noteId, AuthenticationInfo subject) throws IOException;
  @ZeppelinApi public void save(Note note, AuthenticationInfo subject) throws IOException;
  @ZeppelinApi public void remove(String noteId) throws IOException;

  /**
   * Release any underlying resources
   */
  @ZeppelinApi public void close();

  /**
   * chekpoint (versioning) for notebooks (optional)
   */
  @ZeppelinApi public void checkpoint(String noteId, String checkPointName) throws IOException;
}
