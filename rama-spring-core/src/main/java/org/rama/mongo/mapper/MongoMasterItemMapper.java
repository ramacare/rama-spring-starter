package org.rama.mongo.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.rama.entity.master.MasterItem;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public abstract class MongoMasterItemMapper implements IMongoMapper<MasterItem, org.rama.mongo.document.MasterItem> {
}
