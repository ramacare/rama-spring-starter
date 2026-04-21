package org.rama.service.master;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.StatusCode;
import org.rama.entity.master.MasterItem;
import org.rama.repository.master.MasterItemRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MasterItemServiceTest {

    @Mock
    private MasterItemRepository masterItemRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private JPAQueryFactory queryFactory;

    @InjectMocks
    private MasterItemService service;

    @Test
    void translateMaster_returnsItemValue_forThaiLang() {
        MasterItem item = item("Active Value", "Active Value EN");
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCode("$g", "code"))
                .thenReturn(Optional.of(item));

        assertThat(service.translateMaster("$g", "code")).isEqualTo("Active Value");
        assertThat(service.translateMaster("$g", "code", "TH")).isEqualTo("Active Value");
    }

    @Test
    void translateMaster_returnsAlternativeValue_forEnglishLang() {
        MasterItem item = item("Active Value", "Active Value EN");
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCode("$g", "code"))
                .thenReturn(Optional.of(item));

        assertThat(service.translateMaster("$g", "code", "EN")).isEqualTo("Active Value EN");
    }

    @Test
    void translateMaster_returnsEmptyString_whenItemMissing() {
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCode("$g", "missing"))
                .thenReturn(Optional.empty());

        assertThat(service.translateMaster("$g", "missing", "TH")).isEqualTo("");
    }

    @Test
    void translateMasterWithTerminated_2arg_defaultsToThai() {
        MasterItem item = item("Retired Value", "Retired EN");
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCodeWithTerminated("$g", "retired"))
                .thenReturn(Optional.of(item));

        assertThat(service.translateMasterWithTerminated("$g", "retired")).isEqualTo("Retired Value");
    }

    @Test
    void translateMasterWithTerminated_3arg_honoursLang() {
        MasterItem item = item("Retired Value", "Retired EN");
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCodeWithTerminated("$g", "retired"))
                .thenReturn(Optional.of(item));

        assertThat(service.translateMasterWithTerminated("$g", "retired", "EN")).isEqualTo("Retired EN");
    }

    @Test
    void getMasterItemCode_returnsNull_whenItemValueOrGroupKeyIsNull() {
        assertThat(service.getMasterItemCode(null, "value", null)).isNull();
        assertThat(service.getMasterItemCode("$g", null, null)).isNull();
    }

    @Test
    void getMasterItemCode_returnsFetchedCode_fromNarrowQuery() {
        // With RETURNS_DEEP_STUBS, every chained method returns a new mock. The
        // fluent chain query.select(...).from(...).where(...).orderBy(...).fetchFirst()
        // resolves to a single stub path we can pin. We don't assert on the
        // predicate shape (Mockito can't easily compare BooleanExpressions);
        // instead we verify the service returns whatever fetchFirst yields.
        JPAQuery<String> typed = mockTypedQuery("ITEM-CODE");
        when(queryFactory.select(any(StringPath.class))).thenReturn(cast(typed));

        String result = service.getMasterItemCode("$g", "value", null);
        assertThat(result).isEqualTo("ITEM-CODE");
    }

    @Test
    void getMasterItemCode_returnsNull_whenQueryFindsNothing() {
        JPAQuery<String> typed = mockTypedQuery(null);
        when(queryFactory.select(any(StringPath.class))).thenReturn(cast(typed));

        assertThat(service.getMasterItemCode("$g", "missing", "filter")).isNull();
    }

    @SuppressWarnings("unchecked")
    private static <T> JPAQuery<T> mockTypedQuery(T result) {
        JPAQuery<T> query = mock(JPAQuery.class);
        when(query.from(any(com.querydsl.core.types.EntityPath.class))).thenReturn(query);
        when(query.where(any(Predicate.class))).thenReturn(query);
        when(query.orderBy(any(OrderSpecifier.class))).thenReturn(query);
        when(query.fetchFirst()).thenReturn(result);
        return query;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JPAQuery cast(JPAQuery<?> query) {
        return query;
    }

    private static MasterItem item(String value, String valueAlternative) {
        MasterItem item = new MasterItem();
        item.setGroupKey("$g");
        item.setItemCode("code");
        item.setItemValue(value);
        item.setItemValueAlternative(valueAlternative);
        item.setStatusCode(StatusCode.active);
        return item;
    }
}
