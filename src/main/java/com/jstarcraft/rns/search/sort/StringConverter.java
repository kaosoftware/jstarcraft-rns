package com.jstarcraft.rns.search.sort;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;

import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;

import com.jstarcraft.core.utility.StringUtility;
import com.jstarcraft.rns.search.annotation.SearchSort;

/**
 * 字符串转换器
 * 
 * @author Birdy
 *
 */
public class StringConverter implements SortConverter {

    @Override
    public Iterable<IndexableField> convert(Map<Field, SortConverter> context, String path, Field field, SearchSort annotation, String name, Type type, Object data) {
        name = path + StringUtility.DOT + name;
        Collection<IndexableField> fields = new LinkedList<>();
        fields.add(new SortedDocValuesField(name, new BytesRef(data.toString())));
        return fields;
    }

}
