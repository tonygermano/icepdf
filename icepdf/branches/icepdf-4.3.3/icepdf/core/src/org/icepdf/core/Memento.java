/*
 * Copyright 2006-2012 ICEsoft Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.core;

/**
 * Creates a memento containing a snapshot of the internal state.  The state
 * can be retreived when the restore method is called.  This interface should
 * be used by any object that plans to use the Caretaker implementation in the
 * RI.
 *
 *  @since 4.0
 */
public interface Memento {

    /**
     * Restore the state that was caputred when an instance of this object
     * was created. 
     */
    public void restore();
}
