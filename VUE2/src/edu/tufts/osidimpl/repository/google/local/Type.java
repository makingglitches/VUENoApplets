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

package edu.tufts.osidimpl.repository.google.local;

public class Type extends org.osid.shared.Type {

  protected Type(
    String authority,
    String domain,
    String keyword,
    String description
  ) {
    super(authority, domain, keyword, description);
  }

  protected Type(String authority, String domain, String keyword) {
    super(authority, domain, keyword);
  }
}
