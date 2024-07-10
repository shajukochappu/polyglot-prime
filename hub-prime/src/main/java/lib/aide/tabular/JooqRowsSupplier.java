package lib.aide.tabular;

import java.util.ArrayList;
import java.util.List;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.conf.RenderKeywordCase;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

public final class JooqRowsSupplier implements TabularRowsSupplier<JooqRowsSupplier.JooqQuery> {

    private final TabularRowsRequest request;
    private final Table<?> table;
    private final DSLContext dsl;

    private JooqRowsSupplier(final Builder builder) {
        this.request = builder.request;
        this.table = builder.table;
        this.dsl = builder.dsl;
    }

    @Override
    public TabularRowsResponse<JooqQuery> response() {
        final var jooqQuery = query();
        final var query = jooqQuery.query();
        final var result = dsl.fetch(query.getSQL(), jooqQuery.bindValues().toArray());
        final var data = result.intoMaps();

        var lastRow = request.startRow() + data.size();
        if (data.size() < (request.endRow() - request.startRow())) {
            lastRow = -1;
        }

        return new TabularRowsResponse<>(jooqQuery, data, lastRow, null);
    }

    public JooqQuery query() {
        final var selectFields = new ArrayList<Field<?>>();
        final var whereConditions = new ArrayList<Condition>();
        final var bindValues = new ArrayList<Object>();
        final var sortFields = new ArrayList<SortField<?>>();
        final var groupByFields = new ArrayList<Field<?>>();

        // Adding columns to select
        request.valueCols().forEach(col -> selectFields.add(DSL.field(DSL.name(col.field()))));

        // Adding filters
        request.filterModel().forEach((field, filter) -> {
            final var condition = createCondition(field, filter);
            whereConditions.add(condition);
            bindValues.add(filter.filter());
        });

        // Adding sorting
        for (final var sort : request.sortModel()) {
            final var sortField = DSL.field(DSL.name(sort.colId()));
            switch (sort.sort()) {
                case "asc" -> sortFields.add(sortField.asc());
                case "desc" -> sortFields.add(sortField.desc());
            }
        }

        // Adding grouping
        request.rowGroupCols().forEach(col -> {
            final var field = DSL.field(DSL.name(col.field()));
            groupByFields.add(field);
            selectFields.add(field);
        });

        // Adding aggregations
        request.aggregationFunctions().forEach(aggFunc -> {
            aggFunc.columns().forEach(col -> {
                final var field = DSL.field(DSL.name(col));
                final var aggregationField = switch (aggFunc.functionName().toLowerCase()) {
                    case "sum" -> DSL.sum(field.cast(Double.class));
                    case "avg" -> DSL.avg(field.cast(Double.class));
                    case "count" -> DSL.count(field);
                    default ->
                        throw new IllegalArgumentException("Unknown aggregation function: " + aggFunc.functionName());
                };
                selectFields.add(aggregationField);
            });
        });

        // Creating the base query
        final var limit = request.endRow() - request.startRow();
        final var select = groupByFields.isEmpty()
                ? this.dsl.select(selectFields).from(table).where(whereConditions).orderBy(sortFields)
                        .limit(request.startRow(), limit)
                : this.dsl.select(selectFields).from(table).where(whereConditions).groupBy(groupByFields).orderBy(sortFields)
                        .limit(request.startRow(), limit);

        bindValues.add(request.startRow());
        bindValues.add(limit);

        return new JooqQuery(select, bindValues);
    }

    private Condition createCondition(final String field, final TabularRowsRequest.FilterModel filter) {
        final var dslField = DSL.field(DSL.name(field));
        return switch (filter.filterType()) {
            case "text" -> dslField.likeIgnoreCase("%" + filter.filter() + "%");
            case "number" -> dslField.eq(DSL.param(field, filter.filter()));
            case "date" -> dslField.eq(DSL.param(field, filter.filter()));
            default -> throw new IllegalArgumentException("Unknown filter type: " + filter.filterType());
        };
    }

    public static final class Builder {
        private TabularRowsRequest request;
        private Table<?> table;
        private DSLContext dsl;

        public Builder withRequest(final TabularRowsRequest request) {
            this.request = request;
            return this;
        }

        public Builder withTable(final Table<?> table) {
            this.table = table;
            return this;
        }

        public Builder withDSL(final DSLContext dsl) {
            this.dsl = dsl.configuration().derive(new Settings()
                    .withRenderFormatted(true)
                    .withRenderKeywordCase(RenderKeywordCase.UPPER)
                    .withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_QUOTED)).dsl();
            return this;
        }

        public JooqRowsSupplier build() {
            return new JooqRowsSupplier(this);
        }
    }

    public static record JooqQuery(Query query, List<Object> bindValues) {
    }
}
