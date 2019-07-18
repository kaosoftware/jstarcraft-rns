package com.jstarcraft.rns.search.sort;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.IndexableField;

import com.jstarcraft.core.common.reflection.TypeUtility;
import com.jstarcraft.core.utility.ClassUtility;
import com.jstarcraft.core.utility.StringUtility;
import com.jstarcraft.rns.search.annotation.SearchSort;
import com.jstarcraft.rns.search.exception.SearchException;

/**
 * 布尔转换器
 * 
 * @author Birdy
 *
 */
public class BooleanConverter implements SortConverter {

    @Override
    public Iterable<IndexableField> convert(Map<Field, SortConverter> context, String path, Field field, SearchSort annotation, String name, Type type, Object data) {
        name = path + StringUtility.DOT + name;
        Class<?> clazz = TypeUtility.getRawType(type, null);
        clazz = ClassUtility.primitiveToWrapper(clazz);
        if (AtomicBoolean.class == clazz) {
            Collection<IndexableField> fields = new LinkedList<>();
            fields.add(new NumericDocValuesField(name, AtomicBoolean.class.cast(data).get() ? 1L : 0L));
            return fields;
        }
        if (Boolean.class == clazz) {
            Collection<IndexableField> fields = new LinkedList<>();
            fields.add(new NumericDocValuesField(name, Boolean.class.cast(data) ? 1L : 0L));
            return fields;
        }
        throw new SearchException();
    }

}
