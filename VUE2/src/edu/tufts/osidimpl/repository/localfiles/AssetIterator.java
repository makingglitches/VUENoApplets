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

package edu.tufts.osidimpl.repository.localfiles;

public class AssetIterator implements org.osid.repository.AssetIterator {

  private java.util.Iterator iterator = null;

  protected AssetIterator(java.util.Vector vector)
    throws org.osid.repository.RepositoryException {
    this.iterator = vector.iterator();
  }

  public boolean hasNextAsset() throws org.osid.repository.RepositoryException {
    return (this.iterator.hasNext());
  }

  public org.osid.repository.Asset nextAsset()
    throws org.osid.repository.RepositoryException {
    try {
      return (org.osid.repository.Asset) iterator.next();
    } catch (Throwable t) {
      throw new org.osid.repository.RepositoryException(
        org.osid.shared.SharedException.NO_MORE_ITERATOR_ELEMENTS
      );
    }
  }
}
