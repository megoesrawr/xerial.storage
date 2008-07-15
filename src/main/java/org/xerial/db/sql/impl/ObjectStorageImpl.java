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
// ObjectStorageImpl.java
// Since: Jun 26, 2008 3:28:06 PM
//
// $URL$
// $Author$
//--------------------------------------
package org.xerial.db.sql.impl;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.xerial.db.DBErrorCode;
import org.xerial.db.DBException;
import org.xerial.db.Relation;
import org.xerial.db.datatype.DataType;
import org.xerial.db.sql.ByteArray;
import org.xerial.db.sql.DatabaseAccess;
import org.xerial.db.sql.ObjectStorage;
import org.xerial.db.sql.PreparedStatementHandler;
import org.xerial.db.sql.QueryParam;
import org.xerial.db.sql.RelationBuilder;
import org.xerial.db.sql.SQLExpression;
import org.xerial.util.Pair;
import org.xerial.util.Predicate;
import org.xerial.util.StringUtil;
import org.xerial.util.bean.BeanBinder;
import org.xerial.util.bean.BeanBinderSet;
import org.xerial.util.bean.BeanErrorCode;
import org.xerial.util.bean.BeanException;
import org.xerial.util.bean.BeanUtil;
import org.xerial.util.log.Logger;

/**
 * An implementation of the {@link ObjectStorage}
 * 
 * @author leo
 * 
 */
public class ObjectStorageImpl implements ObjectStorage
{
    private static Logger _logger = Logger.getLogger(ObjectStorageImpl.class);

    private DatabaseAccess dbAccess;

    private HashSet<String> registeredTableSet = new HashSet<String>();
    private HashMap<Class< ? >, Relation> relationOfEachClass = new HashMap<Class< ? >, Relation>();
    private HashMap<Class< ? >, String> tableNameOfEachClass = new HashMap<Class< ? >, String>();

    private HashMap<Class< ? >, Class< ? >> associatedClassOfOneToOneRelationship = new HashMap<Class< ? >, Class< ? >>();

    public ObjectStorageImpl(DatabaseAccess dbAccess)
    {
        this.dbAccess = dbAccess;
    }

    public DatabaseAccess getDatabaseAccess()
    {
        return dbAccess;
    }

    public static Date getNowDate()
    {
        Date preciseNow = new Date();
        DateFormat instance = DateFormat.getDateTimeInstance();
        try
        {
            return instance.parse(instance.format(preciseNow));
        }
        catch (ParseException e)
        {
            _logger.error(e);
            throw new IllegalStateException(e);
        }
    }

    public <T> T create(T bean) throws DBException
    {
        Class< ? > beanType = bean.getClass();

        String tableName = getTableName(beanType);
        Relation r = getRelation(beanType);
        try
        {
            Date now = getNowDate();
            setCreatedAtTimeStamp(bean, now);
            setModifiedAtTimeStamp(bean, now);

            final Pair<List<Pair<DataType, String>>, List<ByteArray>> valueData = retrieveColumnValueAndBlobList(r,
                    bean);

            ArrayList<String> valueList = new ArrayList<String>();
            for (Pair<DataType, String> pair : valueData.getFirst())
            {
                valueList.add(pair.getSecond());
            }

            String sql = SQLExpression.fillTemplate("insert into $1($2) values($3)", tableName, StringUtil.join(
                    writableAttributeList(r), ", "), StringUtil.join(valueList, ", "));

            int lastGeneratedID = dbAccess.insertAndRetrieveKeysWithPreparedStatement(sql,
                    new PreparedStatementHandler() {

                        public void setup(PreparedStatement preparedStatement) throws SQLException
                        {
                            int index = 1;
                            for (ByteArray blob : valueData.getSecond())
                            {
                                preparedStatement.setBytes(index++, blob.getBytes());
                            }

                        }
                    });
            setBeanID(bean, lastGeneratedID);
        }
        catch (BeanException e)
        {
            throw new DBException(DBErrorCode.UpdateError, e);
        }

        return bean;
    }

    /**
     * Set a time stamp to the object
     * 
     * @param <T>
     * @param bean
     * @param timeStampParameterName
     *            (createdAt or modifiedAt)
     * @throws BeanException
     */
    private static <T> void setTimeStamp(T bean, String timeStampParameterName, Date timeStamp) throws BeanException
    {
        BeanBinderSet ruleSet = BeanUtil.getBeanLoadRule(bean.getClass());
        BeanBinder binder = ruleSet.findRule(timeStampParameterName);

        // no corresponding time stamp setter (CreatedAt(Date date), etc.) method found
        if (binder == null)
            return;

        try
        {
            binder.getMethod().invoke(bean, new Object[] { timeStamp });
        }
        catch (Exception e)
        {
            throw new BeanException(BeanErrorCode.InvocationTargetException, e);
        }

    }

    public static <T> void setCreatedAtTimeStamp(T bean, Date timeStamp) throws BeanException
    {
        setTimeStamp(bean, "createdAt", timeStamp);
    }

    public static <T> void setModifiedAtTimeStamp(T bean, Date timeStamp) throws BeanException
    {
        setTimeStamp(bean, "modifiedAt", timeStamp);
    }

    private <T, U> boolean isOneToOne(Class<T> parent, Class<U> child)
    {
        return associatedClassOfOneToOneRelationship.containsKey(parent)
                && associatedClassOfOneToOneRelationship.get(parent).equals(child);
    }

    public <T, U> U create(T parentObject, U associatedObject) throws DBException
    {
        setParentBeanID(parentObject, associatedObject);

        if (isOneToOne(parentObject.getClass(), associatedObject.getClass()))
        {
            if (getOne(parentObject, associatedObject.getClass()) != null)
            {
                throw new DBException(DBErrorCode.AssociatedObjectAlreadyExist);
            }
        }

        return create(associatedObject);
    }

    public static <T> void setBeanID(T bean, int id) throws DBException
    {
        setValue(bean, "id", id);
    }

    public static <T, U> void setParentBeanID(T parentObject, U associatedObject) throws DBException
    {
        int parentID = getBeanID(parentObject);
        String parentIDParamName = parentObject.getClass().getSimpleName() + "Id";
        setValue(associatedObject, parentIDParamName, parentID);
    }

    public static <T> void setValue(T bean, String parameterName, Object value) throws DBException
    {
        try
        {
            Class< ? > beanClass = bean.getClass();
            BeanBinderSet ruleSet = BeanUtil.getBeanLoadRule(beanClass);
            BeanBinder binder = ruleSet.findRule(parameterName);
            if (binder == null)
            {
                throw new DBException(DBErrorCode.InvalidBeanClass, "no getter for " + parameterName);
            }
            binder.getMethod().invoke(bean, new Object[] { value });
        }
        catch (Exception e)
        {
            throw new DBException(DBErrorCode.InvalidBeanClass, e);
        }
    }

    public static <T> int getBeanID(T bean) throws DBException
    {
        return Integer.class.cast(getValue(bean, "id"));
    }

    public static <T, U> int getAssociatedBeanID(T bean, Class<U> associatedClass) throws DBException
    {
        String associatedBeanIDName = getAssociatedIDColumnName(associatedClass);
        return Integer.class.cast(getValue(bean, associatedBeanIDName));
    }

    public static <T> String getAssociatedIDColumnName(Class<T> parentClass)
    {
        return parentClass.getSimpleName().toLowerCase() + "Id";
    }

    /**
     * Retrieves the bean value using the getter corresponding to the given
     * parameter name
     * 
     * @param <T>
     * @param bean
     * @param parameterName
     * @return thre result of getSomething() method
     * @throws BeanException
     */
    public static <T> Object getValue(T bean, String parameterName) throws DBException
    {
        try
        {
            BeanBinderSet ruleSet = BeanUtil.getBeanOutputRule(bean.getClass());
            BeanBinder binder = ruleSet.findRule(parameterName);
            return binder.getMethod().invoke(bean, new Object[] {});
        }
        catch (Exception e)
        {
            _logger.error(e);
            throw new DBException(DBErrorCode.InvalidBeanClass, e);
        }
    }

    public static <T> Pair<List<Pair<DataType, String>>, List<ByteArray>> retrieveColumnValueAndBlobList(
            Relation relation, T bean)
    {
        ArrayList<Pair<DataType, String>> valueList = new ArrayList<Pair<DataType, String>>();
        ArrayList<ByteArray> blobList = new ArrayList<ByteArray>();
        for (DataType dt : relation.getDataTypeList())
        {
            if (dt.getName().equals("id"))
                continue; // skip ID attribute
            Object value = null;
            try
            {
                value = getValue(bean, dt.getName());
            }
            catch (Exception e)
            {
                _logger.error(e);
            }

            switch (dt.getType())
            {
            case INTEGER:
            case LONG:
            case DOUBLE:
                // no quotation
                valueList.add(new Pair<DataType, String>(dt, value == null ? "" : value.toString()));
                break;
            case DATETIME:
            {
                Date date = Date.class.cast(value);
                String dateValue = (date == null) ? "" : String.format("'%s'", DateFormat.getDateTimeInstance().format(
                        date));
                valueList.add(new Pair<DataType, String>(dt, dateValue));

                break;
            }
            case BLOB:
            {
                valueList.add(new Pair<DataType, String>(dt, "?"));
                ByteArray blobData = ByteArray.class.cast(value);
                if (blobData != null)
                {
                    blobList.add(blobData);
                }
                else
                    blobList.add(new ByteArray());
                break;
            }
            case BOOLEAN:
            case PASSWORD:
            case STRING:
            case TEXT:
            default:
                // with quotation
                valueList.add(new Pair<DataType, String>(dt, String.format("'%s'", value == null ? "" : value
                        .toString())));
                break;
            }
        }
        return new Pair<List<Pair<DataType, String>>, List<ByteArray>>(valueList, blobList);

    }

    public static List<String> writableAttributeList(Relation relation)
    {
        ArrayList<String> writableAttributeList = new ArrayList<String>();
        for (DataType dt : relation.getDataTypeList())
        {
            if (dt.getName().equals("id"))
                continue; // skip ID attribute
            writableAttributeList.add(dt.getName());
        }
        return writableAttributeList;
    }

    public <T> T get(Class<T> classType, int id) throws DBException
    {
        String tableName = getTableName(classType);
        String sql = SQLExpression.fillTemplate("select * from $1 where id = $2", tableName, id);

        List<T> result = dbAccess.query(sql, classType);
        if (result.size() > 0)
            return result.get(0);
        else
            return null;
    }

    public <T> T get(Class<T> classType, String sql) throws DBException
    {
        List<T> result = dbAccess.query(sql, classType);
        if (result.size() > 0)
            return result.get(0);
        else
            return null;
    }

    public <T, U> List<U> getAll(T startPoint, Class<U> associatedType) throws DBException
    {
        int startPointID = getBeanID(startPoint);
        return getAll(startPoint.getClass(), startPointID, associatedType);
    }

    public <T, U> List<U> getAllWithSorting(T startPoint, Class<U> associatedType) throws DBException
    {
        int startPointID = getBeanID(startPoint);
        String tableName = getTableName(associatedType);
        String parentIDColumnName = getAssociatedIDColumnName(startPoint.getClass());
        String sql = SQLExpression.fillTemplate("select u.* from $1 u where $2 = $3 order by u.id", tableName,
                parentIDColumnName, startPointID);
        return dbAccess.query(sql, associatedType);
    }

    public <T, U> List<U> getAll(T startPoint, Class<U> associatedType, QueryParam queryParam) throws DBException
    {
        int startPointID = getBeanID(startPoint);
        return getAll(startPoint.getClass(), startPointID, associatedType, queryParam);
    }

    public <T, U> List<U> getAll(Class<T> startPointClass, int idOfT, Class<U> associtedType) throws DBException
    {
        String tableName = getTableName(associtedType);
        String parentIDColumnnString = getAssociatedIDColumnName(startPointClass);
        String sql = SQLExpression.fillTemplate("select * from $1 where $2 = $3", tableName, parentIDColumnnString,
                idOfT);
        return dbAccess.query(sql, associtedType);
    }

    public <T, U> List<U> getAll(Class<T> startPointClass, int idOfT, Class<U> associatedType, QueryParam queryParam)
            throws DBException
    {
        String tableName = getTableName(associatedType);
        String parentIDColumnName = getAssociatedIDColumnName(startPointClass);
        String sql = SQLExpression.fillTemplate("select u.* from $1 u where $2 = $3 $4 order by $5", tableName,
                parentIDColumnName, idOfT, (queryParam.getWhereCondition() != null) ? "and "
                        + queryParam.getWhereCondition() : "", (queryParam.getOrderByColumns() != null) ? queryParam
                        .getOrderByColumns() : "u.id");
        return dbAccess.query(sql, associatedType);
    }

    public <T> List<T> getAll(Class<T> classType) throws DBException
    {
        return getAll(classType, (Predicate<T>) null);
    }

    public <T> List<T> getAll(Class<T> classType, String sql) throws DBException
    {
        return dbAccess.query(sql, classType);
    }

    public <T> List<T> getAll(Class<T> classType, Predicate<T> filterPredicate) throws DBException
    {
        String tableName = getTableName(classType);
        String sql = SQLExpression.fillTemplate("select * from $1", tableName);
        if (filterPredicate != null)
            return dbAccess.query(sql, classType, filterPredicate);
        else
            return dbAccess.query(sql, classType);
    }

    public <T, U> U getOne(T startPoint, Class<U> associatedType) throws DBException
    {
        int parentID;
        parentID = getBeanID(startPoint);
        return getOne(startPoint.getClass(), parentID, associatedType);
    }

    public <T, U> U getOne(T startPoint, Class<U> associatedType, int idOfU) throws DBException
    {
        String tableNameOfU = getTableName(associatedType);
        String parentIDColumnName = getAssociatedIDColumnName(startPoint.getClass());
        int parentID = getBeanID(startPoint);

        String sql = SQLExpression.fillTemplate("select * from $1 where $2 = $3 and id = $4", tableNameOfU,
                parentIDColumnName, parentID, idOfU);
        List<U> result = dbAccess.query(sql, associatedType);
        if (result.size() > 0)
            return result.get(0);
        else
            return null;

    }

    public <T, U> U getOne(Class<T> startPointClass, int idOfT, Class<U> associatedType) throws DBException
    {
        String tableNameOfU = getTableName(associatedType);
        String parentIDColumnName = getAssociatedIDColumnName(startPointClass);

        String sql = SQLExpression.fillTemplate("select * from $1 where $2 = $3", tableNameOfU, parentIDColumnName,
                idOfT);
        List<U> result = dbAccess.query(sql, associatedType);
        if (result.size() > 0)
            return result.get(0);
        else
            return null;

    }

    public <T, U> U getOne(Class<T> startPointClass, int idOfT, Class<U> associatedType, int idOfU) throws DBException
    {
        String tableNameOfU = getTableName(associatedType);
        String parentIDColumnName = getAssociatedIDColumnName(startPointClass);

        String sql = SQLExpression.fillTemplate("select * from $1 where $2 = $3 and id = $4", tableNameOfU,
                parentIDColumnName, idOfT, idOfU);
        List<U> result = dbAccess.query(sql, associatedType);
        if (result.size() > 0)
            return result.get(0);
        else
            return null;
    }

    public <T, U> T getParent(U child, Class<T> parentType) throws DBException
    {
        int parentID = getAssociatedBeanID(child, parentType);
        String parentTableName = getTableName(parentType);
        String sql = SQLExpression.fillTemplate("select * from $1 where id = $2", parentTableName, parentID);
        List<T> result = dbAccess.query(sql, parentType);
        if (result == null || result.size() <= 0)
            return null;
        else
            return result.get(0);

    }

    public <T, U> T getParent(Class<T> parentClass, Class<U> childClass, int idOfU) throws DBException
    {
        String parentTableName = getTableName(parentClass);
        String childTableName = getTableName(childClass);
        String sql = SQLExpression.fillTemplate("select t.* from $1 t, $2 u where u.id = $3", parentTableName,
                childTableName, idOfU);
        List<T> result = dbAccess.query(sql, parentClass);
        if (result == null || result.size() <= 0)
            return null;
        else
            return result.get(0);

    }

    public <T, U> void oneToOne(Class<T> from, Class<U> to) throws DBException
    {
        associatedClassOfOneToOneRelationship.put(from, to);
    }

    public <T> void register(String tableName, Class<T> classType) throws DBException
    {
        if (registeredTableSet.contains(tableName))
            return; // already registered

        try
        {
            Relation relation = RelationBuilder.createRelation(classType);
            if (!dbAccess.hasTable(tableName))
            {
                // No corresponding table is found, so create a new table
                String schema = createTableSchema(relation);
                String sql = SQLExpression.fillTemplate("create table $1 ($2)", tableName, schema);

                _logger.info(String.format("create a new table %s", tableName));
                dbAccess.update(sql);
            }

            // TODO It it needed to test duplicate entries?
            tableNameOfEachClass.put(classType, tableName);
            relationOfEachClass.put(classType, relation);
            registeredTableSet.add(tableName);

        }
        catch (BeanException e)
        {
            throw new DBException(DBErrorCode.InvalidBeanClass, e);
        }

    }

    public static <T> String createTableSchema(Relation r) throws BeanException
    {
        LinkedList<String> columnDefList = new LinkedList<String>();
        for (DataType dt : r.getDataTypeList())
        {
            StringBuilder columnDef = new StringBuilder();
            columnDef.append(String.format("%s %s", dt.getName(), dt.getTypeName()));

            if (dt.getName().equals("id"))
            {
                columnDef.append(" primary key autoincrement not null");
                // id attribute must be the first column
                columnDefList.addFirst(columnDef.toString());
            }
            else
                columnDefList.add(columnDef.toString());
        }

        return StringUtil.join(columnDefList, ", ");
    }

    public static <T> String createTableSchema(Class<T> classType) throws BeanException
    {
        Relation r = RelationBuilder.createRelation(classType);
        return createTableSchema(r);
    }

    public <T> void register(Class<T> classType) throws DBException
    {
        String tableName = classType.getSimpleName().toLowerCase();
        register(tableName, classType);
    }

    private String getTableName(Class< ? > beanType) throws DBException
    {
        String tableName = tableNameOfEachClass.get(beanType);
        if (tableName == null)
        {
            // if no corresponding table name for the given beanType was found, register the class
            register(beanType);
            return getTableName(beanType);
        }
        else
            return tableName;
    }

    private Relation getRelation(Class< ? > beanClass)
    {
        return relationOfEachClass.get(beanClass);
    }

    public <T> void saveBlob(Class<T> objectClass, int id, String parameterName, final byte[] blobData)
            throws DBException
    {
        String tableName = getTableName(objectClass);
        String sql = SQLExpression.fillTemplate("update $1 set $2 = ? where id = $3", tableName, parameterName, id);
        dbAccess.updateWithPreparedStatement(sql, new PreparedStatementHandler() {
            public void setup(PreparedStatement preparedStatement) throws SQLException
            {
                preparedStatement.setBytes(1, blobData);
            }
        });
    }

    public <T> void saveBlob(T object, String parameterName, final byte[] blobData) throws DBException
    {
        saveBlob(object.getClass(), getBeanID(object), parameterName, blobData);
    }

    public static <T> Pair<String, List<ByteArray>> createUpdateStatement(Relation relation, T bean)
    {
        Pair<List<Pair<DataType, String>>, List<ByteArray>> valueData = retrieveColumnValueAndBlobList(relation, bean);
        ArrayList<String> setStatementList = new ArrayList<String>();
        int i = 0;
        for (Pair<DataType, String> pair : valueData.getFirst())
        {
            String columnName = pair.getFirst().getName();
            if (columnName.equals("createdAt"))
                continue;
            setStatementList.add(String.format("%s = %s", columnName, pair.getSecond()));
        }
        return new Pair<String, List<ByteArray>>(StringUtil.join(setStatementList, ", "), valueData.getSecond());
    }

    public <T> void save(T bean) throws DBException
    {
        Class< ? > beanType = bean.getClass();
        String tableName = getTableName(beanType);
        Relation r = getRelation(beanType);

        try
        {
            int id = getBeanID(bean);

            Date now = getNowDate();
            setModifiedAtTimeStamp(bean, now);

            final Pair<String, List<ByteArray>> setValueList = createUpdateStatement(r, bean);
            String sql = SQLExpression.fillTemplate("update $1 set $2 where id = $3", tableName, setValueList
                    .getFirst(), id);

            dbAccess.updateWithPreparedStatement(sql, new PreparedStatementHandler() {
                public void setup(PreparedStatement preparedStatement) throws SQLException
                {
                    int index = 1;
                    for (ByteArray blob : setValueList.getSecond())
                    {
                        preparedStatement.setBytes(index++, blob.getBytes());
                    }
                }
            });

        }
        catch (BeanException e)
        {
            throw new DBException(DBErrorCode.InvalidBeanClass, e);
        }
    }

    public <T> void saveAll(Class<T> classType, Collection<T> objectList) throws DBException
    {
        ArrayList<String> updateStatementList = new ArrayList<String>();

        String tableName = getTableName(classType);
        Relation r = getRelation(classType);

        Date now = getNowDate();
        try
        {
            dbAccess.update("begin transaction");
            for (T bean : objectList)
            {
                int id = getBeanID(bean);

                setModifiedAtTimeStamp(bean, now);

                final Pair<String, List<ByteArray>> setValueList = createUpdateStatement(r, bean);
                String sql = SQLExpression.fillTemplate("update $1 set $2 where id = $3", tableName, setValueList
                        .getFirst(), id);

                dbAccess.updateWithPreparedStatement(sql, new PreparedStatementHandler() {
                    public void setup(PreparedStatement preparedStatement) throws SQLException
                    {
                        int index = 1;
                        for (ByteArray blob : setValueList.getSecond())
                        {
                            preparedStatement.setBytes(index++, blob.getBytes());
                        }
                    }
                });
            }
            dbAccess.update("commit");
        }
        catch (Exception e)
        {
            dbAccess.update("rollback");
            throw new DBException(DBErrorCode.UpdateError, e);
        }

    }

    public <T> byte[] getBlob(Class<T> objectClass, int id, String parameterName) throws DBException
    {
        String tableName = getTableName(objectClass);
        String sql = SQLExpression.fillTemplate("select $1 from $2 where id = $3", parameterName, tableName, id);

        return null;
    }

    public <T> byte[] getBlob(T object, String parameterName) throws DBException
    {
        return getBlob(object.getClass(), getBeanID(object), parameterName);
    }

    public <T> T get(Class<T> classType, QueryParam queryParam) throws DBException
    {
        List<T> result = getAll(classType, queryParam);
        return result.size() > 0 ? result.get(0) : null;
    }

    public <T> List<T> getAll(Class<T> classType, QueryParam queryParam) throws DBException
    {
        assert (queryParam != null);
        String sql = SQLExpression.fillTemplate("select * from $1 $2", getTableName(classType), queryParam
                .toSQLFragment());
        return dbAccess.query(sql, classType);
    }

    public <T> int count(Class<T> classType) throws DBException
    {
        return count(classType, new QueryParam());
    }

    public <T> int count(Class<T> classType, QueryParam queryParam) throws DBException
    {
        assert (queryParam != null);
        String sql = SQLExpression.fillTemplate("select count(*) as count from $1 $2", getTableName(classType),
                queryParam.toSQLFragment());
        List<ResultCount> countList = dbAccess.query(sql, ResultCount.class);
        if (countList.size() > 0)
            return countList.get(0).getCount();
        else
            return 0;
    }

    public <T> void delete(T object) throws DBException
    {
        int id = getBeanID(object);
        delete(object.getClass(), id);
    }

    public <T> void delete(Class<T> objectType, int id) throws DBException
    {
        String sql = SQLExpression.fillTemplate("delete from $1 where id = $2", getTableName(objectType), id);
        dbAccess.update(sql);
    }

}
