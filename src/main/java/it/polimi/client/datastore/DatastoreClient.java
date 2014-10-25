package it.polimi.client.datastore;

import com.google.appengine.api.datastore.*;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.ClientBase;
import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.generator.AutoGenerator;
import com.impetus.kundera.index.IndexManager;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.ClientMetadata;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.Relation;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.metadata.model.type.AbstractManagedType;
import com.impetus.kundera.persistence.EntityManagerFactoryImpl.KunderaMetadata;
import com.impetus.kundera.persistence.EntityReader;
import com.impetus.kundera.persistence.context.jointable.JoinTableData;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.property.accessor.EnumAccessor;
import it.polimi.client.datastore.query.DatastoreQuery;
import it.polimi.client.datastore.query.QueryBuilder;
import org.apache.commons.lang.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.util.*;

/**
 * @author Fabio Arcidiacono.
 *         <p>The gateway to CRUD operations on database, except for queries.</p>
 */
public class DatastoreClient extends ClientBase implements Client<DatastoreQuery>, AutoGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DatastoreClient.class);
    private EntityReader reader;
    private DatastoreService datastore;

    protected DatastoreClient(final KunderaMetadata kunderaMetadata, Map<String, Object> properties,
                              String persistenceUnit, final ClientMetadata clientMetadata, IndexManager indexManager,
                              EntityReader reader, final DatastoreService datastore) {
        super(kunderaMetadata, properties, persistenceUnit);
        this.reader = reader;
        this.datastore = datastore;
        this.indexManager = indexManager;
        this.clientMetadata = clientMetadata;
    }

    @Override
    public void close() {
        this.indexManager.flush();
        this.reader = null;
        externalProperties = null;
    }

    @Override
    public EntityReader getReader() {
        return reader;
    }

    @Override
    public Class<DatastoreQuery> getQueryImplementor() {
        return DatastoreQuery.class;
    }

    /* TODO decidere
     *
     * 1. check for unsupported types? o comunque intercettare
     *    element collections e salvarle con la colonna per
     *    indicare il tipo? non lo fanno le altre implementazioni, andrebbero
     *    cambiare tutte le implementazioni che ci interessano
     *
     * 2. test con string id e long id inseriti dall'utente
     *
     * 3. chiedere sul gruppo:
     *      - semantica di:
     *          -  public <E> List<E> find(Class<E> entityClass, Map<String, String> embeddedColumnMap)
     *          -  public <E> List<E> findAll(Class<E> entityClass, String[] columnsToSelect, Object... keys)
     *      - se è possibile recuperare entityMetadata da joinTableName
     *
     * 3. settare indici per le query sui campi delle relazioni:
     *      - quando si salvano (initializeRelations)
     *      - nelle due colonne delle joinTables (persistJoinTable)
     *      - nelle embedded entities?
     *    magari usare annotation, dovrebbe essere possibile, dato il field, recuperare le sue annotation.
     *    Per settare indici setProperty(), altrimenti setUnindexedProperty()
     *
     * 4. tutte le entity figlie di una root fittizia? per averle
     *    nello stesso entity group --> magari impostarlo nelle config
     *    per rendelo disponibile all'untete che piò specificare anche il nome
     *    della root entity
     *
     */

    /*---------------------------------------------------------------------------------*/
    /*----------------------------- PERSIST OPERATIONS -------------------------------*/

    @Override
    public Object generate() {
        /*
         * use random UUID instead of datastore generated
         * since here is not available the class of the entity to be persisted.
         */
        return UUID.randomUUID().toString();
    }

    @Override
    protected void onPersist(EntityMetadata entityMetadata, Object entity, Object id, List<RelationHolder> rlHolders) {
        System.out.println("DatastoreClient.onPersist");
        System.out.println("entityMetadata = [" + entityMetadata + "], entity = [" + entity + "], id = [" + id + "], rlHolders = [" + rlHolders + "]\n");

        MetamodelImpl metamodel = KunderaMetadataManager.getMetamodel(kunderaMetadata,
                entityMetadata.getPersistenceUnit());
        EntityType entityType = metamodel.entity(entityMetadata.getEntityClazz());

        Entity gaeEntity = new Entity(entityMetadata.getTableName(), (String) id);

        handleAttributes(gaeEntity, entity, metamodel, entityMetadata, entityType.getAttributes());
        handleRelations(gaeEntity, entityMetadata, rlHolders);
        /* discriminator column is used for JPA inheritance */
        handleDiscriminatorColumn(gaeEntity, entityType);

        System.out.println("\n" + gaeEntity + "\n");

        datastore.put(gaeEntity);
    }

    private void handleAttributes(Entity gaeEntity, Object entity, MetamodelImpl metamodel, EntityMetadata metadata, Set<Attribute> attributes) {
        String idAttribute = ((AbstractAttribute) metadata.getIdAttribute()).getJPAColumnName();
        for (Attribute attribute : attributes) {
            /*
             * By pass ID attribute, is redundant since is also stored within the Key.
             * By pass associations (i.e. relations) that are handled in handleRelations()
             */
            if (!attribute.isAssociation() && !((AbstractAttribute) attribute).getJPAColumnName().equals(idAttribute)) {
                if (metamodel.isEmbeddable(((AbstractAttribute) attribute).getBindableJavaType())) {
                    processEmbeddableAttribute(gaeEntity, entity, attribute, metamodel, metadata);
                } else {
                    processAttribute(gaeEntity, entity, attribute);
                }
            }
        }
    }

    private void processAttribute(PropertyContainer gaeEntity, Object entity, Attribute attribute) {
        Field field = (Field) attribute.getJavaMember();
        Object valueObj = PropertyAccessorHelper.getObject(entity, field);
        String jpaColumnName = ((AbstractAttribute) attribute).getJPAColumnName();

        /* TODO
         * @ElementCollection fields falls here thus are saved without further checks
         */

        if (((Field) attribute.getJavaMember()).getType().isEnum()) {
            valueObj = valueObj.toString();
        }
        if (valueObj != null) {
            System.out.println("field = [" + field.getName() + "], jpaColumnName = [" + jpaColumnName + "], valueObj = [" + valueObj + "]");
            gaeEntity.setProperty(jpaColumnName, valueObj);
        }
    }

    private void processEmbeddableAttribute(Entity gaeEntity, Object entity, Attribute attribute, MetamodelImpl metamodel, EntityMetadata metadata) {
        Field field = (Field) attribute.getJavaMember();
        String jpaColumnName = ((AbstractAttribute) attribute).getJPAColumnName();
        Object embeddedObj = PropertyAccessorHelper.getObject(entity, field);
        System.out.println("field = [" + field.getName() + "], jpaColumnName = [" + jpaColumnName + "], embeddedObj = [" + embeddedObj + "]");

        EmbeddedEntity embeddedEntity = new EmbeddedEntity();
        EmbeddableType embeddable = metamodel.embeddable(((AbstractAttribute) attribute).getBindableJavaType());
        Set<Attribute> embeddedAttributes = embeddable.getAttributes();
        for (Attribute embeddedAttribute : embeddedAttributes) {
            processAttribute(embeddedEntity, embeddedObj, embeddedAttribute);
        }
        gaeEntity.setProperty(jpaColumnName, embeddedEntity);
    }

    private void handleRelations(Entity gaeEntity, EntityMetadata entityMetadata, List<RelationHolder> rlHolders) {
        if (rlHolders != null && !rlHolders.isEmpty()) {
            for (RelationHolder rh : rlHolders) {
                String jpaColumnName = rh.getRelationName();
                String fieldName = entityMetadata.getFieldName(jpaColumnName);
                Relation relation = entityMetadata.getRelation(fieldName);
                String targetId = (String) rh.getRelationValue();

                if (relation != null && jpaColumnName != null && targetId != null) {
                    Key targetKey = KeyFactory.createKey(relation.getTargetEntity().getSimpleName(), targetId);
                    System.out.println("field = [" + fieldName + "], jpaColumnName = [" + jpaColumnName + "], targetKey = [" + targetKey + "]");
                    gaeEntity.setProperty(jpaColumnName, targetKey);
                }
            }
        }
    }

    private void handleDiscriminatorColumn(Entity gaeEntity, EntityType entityType) {
        String discrColumn = ((AbstractManagedType) entityType).getDiscriminatorColumn();
        String discrValue = ((AbstractManagedType) entityType).getDiscriminatorValue();

        if (discrColumn != null && discrValue != null) {
            System.out.println("discrColumn = [" + discrColumn + "], discrValue = [" + discrValue + "]");
            gaeEntity.setProperty(discrColumn, discrValue);
        }
    }

    /*
     * persist join table for ManyToMany
     *
     * for example:
     *  -----------------------------------------------------------------------
     *  |                    EMPLOYEE_PROJECT (joinTableName)                  |
     *  -----------------------------------------------------------------------
     *  | EMPLOYEE_ID (joinColumnName)  |  PROJECT_ID (inverseJoinColumnName)  |
     *  -----------------------------------------------------------------------
     *  |          id (owner)           |             id (child)               |
     *  -----------------------------------------------------------------------
     */
    @Override
    public void persistJoinTable(JoinTableData joinTableData) {
        System.out.println("DatastoreClient.persistJoinTable");

        String joinTableName = joinTableData.getJoinTableName();
        String joinColumnName = joinTableData.getJoinColumnName();
        String inverseJoinColumnName = joinTableData.getInverseJoinColumnName();
        Map<Object, Set<Object>> joinTableRecords = joinTableData.getJoinTableRecords();

        // TODO cannot use this, in getColumnsById and findIdsByColumn not able to get entities class
        // Class joinColumnClass = joinTableData.getEntityClass();
        // Class inverseJoinColumnClass = null;
        // EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, joinColumnClass);
        // for (Relation relation : entityMetadata.getRelations()) {
        //      if (relation.isRelatedViaJoinTable() && relation.getJoinTableMetadata().getJoinTableName().equals(joinTableName)) {
        //          inverseJoinColumnClass = relation.getTargetEntity();
        //          break;
        //      }
        // }
        // if (inverseJoinColumnClass == null) {
        //      throw new KunderaException("Cannot find ManyToMany relation in " + joinColumnClass);
        // }

        for (Object owner : joinTableRecords.keySet()) {
            Set<Object> children = joinTableRecords.get(owner);
            //Key ownerKey = KeyFactory.createKey(joinColumnClass.getSimpleName(), (String) owner);
            for (Object child : children) {
                //Key childKey = KeyFactory.createKey(inverseJoinColumnClass.getSimpleName(), (String) child);
                /* let datastore generate ID for the entity */
                Entity gaeEntity = new Entity(joinTableName);
                // gaeEntity.setProperty(joinColumnName, ownerKey);
                gaeEntity.setProperty(joinColumnName, owner);
                // gaeEntity.setProperty(inverseJoinColumnName, childKey);
                gaeEntity.setProperty(inverseJoinColumnName, child);
                datastore.put(gaeEntity);

                System.out.println(gaeEntity);
            }
        }
    }

    /*---------------------------------------------------------------------------------*/
    /*------------------------------ FIND OPERATIONS ----------------------------------*/

    /*
     * it's called to find detached entities
     */
    @Override
    public Object find(Class entityClass, Object id) {
        System.out.println("DatastoreClient.find");
        System.out.println("entityClass = [" + entityClass.getSimpleName() + "], id = [" + id + "]");

        try {
            EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entityClass);
            Entity gaeEntity = get(entityMetadata.getTableName(), id);
            if (gaeEntity == null) {
                /* case not found */
                return null;
            }
            System.out.println(gaeEntity);
            return initializeEntity(gaeEntity, entityClass);
        } catch (InstantiationException e) {
            e.printStackTrace();
            throw new KunderaException(e.getMessage());
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            throw new KunderaException(e.getMessage());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new KunderaException(e.getMessage());
        }
    }

    private Entity get(String kind, Object id) {
        try {
            Key key;
            if (id instanceof Key) {
                /* case id is field retrieved from datastore */
                key = (Key) id;
            } else {
                key = KeyFactory.createKey(kind, (String) id);
            }
            return datastore.get(key);
        } catch (EntityNotFoundException e) {
            System.out.println("\tNot found {kind = [" + kind + "], id = [" + id + "]}");
            return null;
        }
    }

    private EnhanceEntity initializeEntity(Entity gaeEntity, Class entityClass) throws IllegalAccessException, InstantiationException, ClassNotFoundException {
        System.out.println("DatastoreClient.initializeEntity");

        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entityClass);
        MetamodelImpl metamodel = KunderaMetadataManager.getMetamodel(kunderaMetadata,
                entityMetadata.getPersistenceUnit());
        EntityType entityType = metamodel.entity(entityMetadata.getEntityClazz());

        Map<String, Object> relationMap = new HashMap<String, Object>();
        Object entity = entityMetadata.getEntityClazz().newInstance();

        initializeID(entityMetadata, gaeEntity, entity);
        Set<Attribute> attributes = entityType.getAttributes();
        for (Attribute attribute : attributes) {
            if (!attribute.isAssociation()) {
                if (metamodel.isEmbeddable(((AbstractAttribute) attribute).getBindableJavaType())) {
                    initializeEmbeddedAttribute(gaeEntity, entity, attribute, metamodel);
                } else {
                    initializeAttribute(gaeEntity, entity, attribute);
                }
            } else {
                initializeRelation(gaeEntity, attribute, relationMap);
            }
        }
        System.out.println(entity + "\n\n");

        return new EnhanceEntity(entity, gaeEntity.getKey().getName(), relationMap.isEmpty() ? null : relationMap);
    }

    private void initializeID(EntityMetadata entityMetadata, Entity gaeEntity, Object entity) {
        String jpaColumnName = ((AbstractAttribute) entityMetadata.getIdAttribute()).getJPAColumnName();
        String id = gaeEntity.getKey().getName();
        System.out.println("jpaColumnName = [" + jpaColumnName + "], fieldValue = [" + id + "]");
        PropertyAccessorHelper.setId(entity, entityMetadata, id);
    }

    private void initializeAttribute(PropertyContainer gaeEntity, Object entity, Attribute attribute) {
        String jpaColumnName = ((AbstractAttribute) attribute).getJPAColumnName();
        Object fieldValue = gaeEntity.getProperties().get(jpaColumnName);

        if (((Field) attribute.getJavaMember()).getType().isEnum()) {
            EnumAccessor accessor = new EnumAccessor();
            fieldValue = accessor.fromString(((AbstractAttribute) attribute).getBindableJavaType(), fieldValue.toString());
        }
        if (jpaColumnName != null && fieldValue != null) {
            System.out.println("jpaColumnName = [" + jpaColumnName + "], fieldValue = [" + fieldValue + "]");
            PropertyAccessorHelper.set(entity, (Field) attribute.getJavaMember(), fieldValue);
        }
    }

    private void initializeEmbeddedAttribute(Entity gaeEntity, Object entity, Attribute attribute, MetamodelImpl metamodel) throws IllegalAccessException, InstantiationException {
        String jpaColumnName = ((AbstractAttribute) attribute).getJPAColumnName();
        EmbeddedEntity embeddedEntity = (EmbeddedEntity) gaeEntity.getProperties().get(jpaColumnName);

        if (jpaColumnName != null && embeddedEntity != null) {
            System.out.println("jpaColumnName = [" + jpaColumnName + "], embeddedEntity = [" + embeddedEntity + "]");

            EmbeddableType embeddable = metamodel.embeddable(((AbstractAttribute) attribute).getBindableJavaType());
            Object embeddedObj = embeddable.getJavaType().newInstance();
            Set<Attribute> embeddedAttributes = embeddable.getAttributes();
            for (Attribute embeddedAttribute : embeddedAttributes) {
                initializeAttribute(embeddedEntity, embeddedObj, embeddedAttribute);
            }
            PropertyAccessorHelper.set(entity, (Field) attribute.getJavaMember(), embeddedObj);
        }
    }

    private void initializeRelation(Entity gaeEntity, Attribute attribute, Map<String, Object> relationMap) {
        String jpaColumnName = ((AbstractAttribute) attribute).getJPAColumnName();
        Object fieldValue = gaeEntity.getProperties().get(jpaColumnName);
        System.out.println("jpaColumnName = [" + jpaColumnName + "], fieldValue = [" + fieldValue + "]");

        if (jpaColumnName != null && fieldValue != null) {
            relationMap.put(jpaColumnName, fieldValue);
        }
    }

    @Override
    public <E> List<E> findAll(Class<E> entityClass, String[] columnsToSelect, Object... keys) {
        System.out.println("DatastoreClient.findAll");
        System.out.println("entityClass = [" + entityClass + "], columnsToSelect = [" + columnsToSelect + "], keys = [" + keys + "]");

        // TODO review this, use columnsToSelect
        // List results = new ArrayList();
        // for (Object key : keys) {
        //     Object object = this.find(entityClass, key);
        //     if (object != null) {
        //         results.add(object);
        //     }
        // }
        // return results;
        throw new NotImplementedException();
    }

    @Override
    public <E> List<E> find(Class<E> entityClass, Map<String, String> embeddedColumnMap) {
        System.out.println("DatastoreClient.find");
        System.out.println("entityClass = [" + entityClass + "],");
        String out = "embeddedColumnMap = [\n";
        for (String key : embeddedColumnMap.keySet()) {
            out += "\tkey = [" + key + "], value = [" + embeddedColumnMap.get(key) + "]\n";
        }
        out += "]";
        System.out.println(out);

        // TODO Auto-generated method stub
        throw new NotImplementedException();
        // return null;
    }

    /*
     * used to retrieve relation for OneToMany (ManyToOne inverse relation),
     * is supposed to retrieve the initialized objects.
     *
     * for example:
     *      select * from EmployeeMTObis (entityClass)
     *      where DEPARTMENT_ID (colName) equals (colValue)
     */
    @Override
    public List<Object> findByRelation(String colName, Object colValue, Class entityClass) {
        System.out.println("DatastoreClient.findByRelation");
        System.out.println("colName = [" + colName + "], colValue = [" + colValue + "], entityClazz = [" + entityClass + "]");

        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entityClass);
        String fieldName = entityMetadata.getFieldName(colName);
        Relation relation = entityMetadata.getRelation(fieldName);
        Key targetKey = KeyFactory.createKey(relation.getTargetEntity().getSimpleName(), (String) colValue);

        Query q = generateRelationQuery(entityClass.getSimpleName(), colName, targetKey);
        q.setKeysOnly();

        List<Object> results = new ArrayList<Object>();
        List<Entity> entities = datastore.prepare(q).asList(FetchOptions.Builder.withDefaults());
        if (!entities.isEmpty()) {
            for (Entity entity : entities) {
                results.add(find(entityClass, entity.getKey()));
            }
        }
        return results;
    }

    /*
     * used to retrieve owner-side relation for ManyToMany,
     * is supposed to retrieve the objects id.
     *
     * for example:
     *      select PROJECT_ID (columnName) from EMPLOYEE_PROJECT (tableName)
     *      where EMPLOYEE_ID (pKeyColumnName) equals (pKeyColumnValue)
     *
     */
    @Override
    public <E> List<E> getColumnsById(String schemaName, String tableName, String pKeyColumnName, String columnName, Object pKeyColumnValue, Class columnJavaType) {
        System.out.println("DatastoreClient.getColumnsById");
        System.out.println("schemaName = [" + schemaName + "], tableName = [" + tableName + "], pKeyColumnName = [" + pKeyColumnName + "], columnName = [" + columnName + "], pKeyColumnValue = [" + pKeyColumnValue + "], columnJavaType = [" + columnJavaType + "]");

        Query q = generateRelationQuery(tableName, pKeyColumnName, pKeyColumnValue);

        List<E> results = new ArrayList<E>();
        List<Entity> entities = datastore.prepare(q).asList(FetchOptions.Builder.withDefaults());
        System.out.println(columnName + " for " + pKeyColumnName + "[" + pKeyColumnValue + "]:");
        if (!entities.isEmpty()) {
            for (Entity entity : entities) {
                System.out.println("\t" + entity.getProperty(columnName));
                /*
                 * TODO handle this case
                 * se qui si prova a fare una get(kind, entity.getProperty(columnName))
                 * e viene ritornato null, c'è una broken reference, cioè un employee
                 * in relazione con un project che non esiste più, se non sia fa niente
                 * employee.getProjects() avrà un elemento a null.
                 * Bisognerebbe eliminare la riga dalla tabella di join se qui la get
                 * ritorna null.
                 * per farlo però serve il Kind, bisognerebbe salvare (dappertutto)
                 * al posto che solo l'id, la chiave come valore della relazione nel database.
                 *
                 */
                results.add((E) entity.getProperty(columnName));
            }
        }
        System.out.println();
        return results;
    }

    /*
     * used to retrieve target-side relation for ManyToMany,
     * is supposed to retrieve the objects id.
     *
     * for example:
     *      select EMPLOYEE_ID (pKeyName) from EMPLOYEE_PROJECT (tableName)
     *      where PROJECT_ID (columnName) equals (columnValue)
     *
     */
    @Override
    public Object[] findIdsByColumn(String schemaName, String tableName, String pKeyName, String columnName, Object columnValue, Class entityClazz) {
        System.out.println("DatastoreClient.findIdsByColumn");
        System.out.println("schemaName = [" + schemaName + "], tableName = [" + tableName + "], pKeyName = [" + pKeyName + "], columnName = [" + columnName + "], columnValue = [" + columnValue + "], entityClazz = [" + entityClazz + "]");

        Query q = generateRelationQuery(tableName, columnName, columnValue);

        List<Object> results = new ArrayList<Object>();
        List<Entity> entities = datastore.prepare(q).asList(FetchOptions.Builder.withDefaults());
        System.out.println(pKeyName + " for " + columnName + "[" + columnValue + "]:");
        if (!entities.isEmpty()) {
            for (Entity entity : entities) {
                System.out.println("\t" + entity.getProperty(pKeyName));
                results.add(entity.getProperty(pKeyName));
            }
        }
        System.out.println();
        return results.toArray();
    }

    /*---------------------------------------------------------------------------------*/
    /*----------------------------- DELETE OPERATIONS ----------------------------------*/

    @Override
    public void delete(Object entity, Object pKey) {
        System.out.println("DatastoreClient.delete");
        System.out.println("entity = [" + entity + "], pKey = [" + pKey + "]");

        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entity.getClass());
        Key key = KeyFactory.createKey(entityMetadata.getTableName(), (String) pKey);
        datastore.delete(key);

        System.out.println();
    }

    /*
     * used to delete relation for ManyToMany
     *
     * for example:
     *      delete from EMPLOYEE_PROJECT (tableName)
     *      where EMPLOYEE_ID (columnName) equals (columnValue)
     *
     */
    @Override
    public void deleteByColumn(String schemaName, String tableName, String columnName, Object columnValue) {
        System.out.println("DatastoreClient.deleteByColumn");
        System.out.println("schemaName = [" + schemaName + "], tableName = [" + tableName + "], columnName = [" + columnName + "], columnValue = [" + columnValue + "]");

        Query q = generateRelationQuery(tableName, columnName, columnValue);
        q.setKeysOnly();

        List<Entity> entities = datastore.prepare(q).asList(FetchOptions.Builder.withDefaults());
        if (!entities.isEmpty()) {
            for (Entity entity : entities) {
                datastore.delete(entity.getKey());
            }
        }
    }

    private Query generateRelationQuery(String tableName, String columnName, Object columnValue) {
        Query query = new Query(tableName)
                .setFilter(new Query.FilterPredicate(columnName,
                        Query.FilterOperator.EQUAL,
                        columnValue));
        System.out.println("\n" + query + "\n");
        return query;
    }

    public List<Object> executeQuery(QueryBuilder builder) {
        System.out.println("DatastoreClient.executeQuery");
        System.out.println("\n" + builder.getQuery() + "\n");

        List<Object> results = new ArrayList<Object>();
        List<Entity> entities = datastore.prepare(builder.getQuery()).asList(FetchOptions.Builder.withDefaults());
        for (Entity entity : entities) {
            System.out.println(entity);
            try {
                EnhanceEntity ee = initializeEntity(entity, builder.getEntityClass());
                if (!builder.holdRelationships()) {
                    /* comes from DatastoreQuery.populateEntities */
                    results.add(ee.getEntity());
                } else {
                    /* comes from DatastoreQuery.recursivelyPopulateEntities */
                    results.add(ee);
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return results;
    }
}
