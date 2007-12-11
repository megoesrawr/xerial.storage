/*--------------------------------------------------------------------------
 *  Copyright 2007 Taro L. Saito
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *--------------------------------------------------------------------------*/
//--------------------------------------
// XerialJ
//
// LongType.java
// Since: Dec 11, 2007 11:28:25 AM
//
// $URL$
// $Author$
//--------------------------------------
package org.xerial.db.datatype;

/**
 * {@link Long} data type
 * @author leo
 *
 */
public class LongType extends DataTypeBase
{

    /**
     * @param name parameter name
     */
    public LongType(String name)
    {
        super(name);
    }

    /**
     * @param name
     * @param isPrimaryKey
     * @param isNotNull
     */
    public LongType(String name, boolean isPrimaryKey, boolean isNotNull)
    {
        super(name, isPrimaryKey, isNotNull);
    }



    /**
     * @param name
     * @param isPrimaryKey
     */
    public LongType(String name, boolean isPrimaryKey)
    {
        super(name, isPrimaryKey);
    }



    public String getTypeName()
    {
        return "long";
    }

}
