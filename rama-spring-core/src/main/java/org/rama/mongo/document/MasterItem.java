package org.rama.mongo.document;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@org.springframework.data.mongodb.core.mapping.Document
@Data
@NoArgsConstructor
public class MasterItem extends org.rama.entity.master.MasterItem {
}
