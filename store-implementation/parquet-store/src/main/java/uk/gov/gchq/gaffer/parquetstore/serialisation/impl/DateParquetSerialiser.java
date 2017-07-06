/*
 * Copyright 2017. Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.gchq.gaffer.parquetstore.serialisation.impl;

import uk.gov.gchq.gaffer.exception.SerialisationException;
import uk.gov.gchq.gaffer.parquetstore.serialisation.ParquetSerialiser;

import java.util.Date;

public class DateParquetSerialiser implements ParquetSerialiser<Date> {
    private static final long serialVersionUID = 3798684785664364539L;

    @Override
    public String getParquetSchema(final String colName) {
        return "optional int64 " + colName + ";";
    }

    @Override
    public Object[] serialise(final Date object) throws SerialisationException {
        return new Object[]{object.getTime()};
    }

    @Override
    public Date deserialise(final Object[] objects) throws SerialisationException {
        if (objects.length == 1) {
            if (objects[0] instanceof Long) {
                return new Date((long) objects[0]);
            } else if (objects[0] == null) {
                return null;
            }
        }
        throw new SerialisationException("Could not deserialise objects to a java.util.Date");
    }

    @Override
    public Date deserialiseEmpty() throws SerialisationException {
        throw new SerialisationException("Cannot deserialise the empty bytes to a java.util.Date");
    }

    @Override
    public boolean preservesObjectOrdering() {
        return true;
    }

    @Override
    public Object[] serialiseNull() {
        return new Comparable[0];
    }

    @Override
    public boolean canHandle(final Class clazz) {
        return Date.class.equals(clazz);
    }
}
