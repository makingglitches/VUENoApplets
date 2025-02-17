/*
 * Copyright 2003-2010 Tufts University  Licensed under the
 * Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package edu.tufts.vue.ontology.ui;

/*
 * OntologySelectionEvent.java
 *
 * Created on May 18, 2007, 1:13 PM
 *
 * @author dhelle01
 */
public class OntologySelectionEvent {

  private TypeList list;

  public OntologySelectionEvent(TypeList list) {
    this.list = list;
  }

  public TypeList getSelection() {
    return list;
  }
}
