import BusyPlaceholder from '@/components/BusyLoaderWrapper/BusyPlaceholder'
import { getRocAucConfig } from '@/components/Indicator/utils'
import { useParseRocAuc } from '@/domain/datastore/hooks/useParseDatastore'
import React from 'react'
import { WidgetConfig, WidgetGroupType, WidgetRendererProps } from '@starwhale/core/types'
import { WidgetPlugin } from '@starwhale/core/widget'
import { UI_DATA } from '@starwhale/core/form/schemas/fields'

const PlotlyVisualizer = React.lazy(
    () => import(/* webpackChunkName: "PlotlyVisualizer" */ '@/components/Indicator/PlotlyVisualizer')
)

export const CONFIG: WidgetConfig = {
    type: 'ui:panel:rocauc',
    group: WidgetGroupType.PANEL,
    name: 'Roc Auc',
    fieldConfig: {
        schema: {
            /**
             * framework define field
             * 1. tableName used to select table names
             */
            tableName: {
                /**
                 * framework define field type
                 * 1. use type = 'array' to make field multiple
                 */
                // type: 'array',
            },
            x: {
                title: 'X',
                type: 'string',
            },
            y: {
                title: 'Y',
                type: 'string',
            },
        },
        uiSchema: {
            x: {
                /**
                 * framework define field uiSchema
                 * 1. UI_DATA.DataTableColumns will auto fill this field by columnTypes
                 */
                'ui:data': UI_DATA.DataTableColumns,
            },
            y: {
                'ui:data': UI_DATA.DataTableColumns,
            },
        },
    },
}

function PanelRocAucWidget(props: WidgetRendererProps<any, any>) {
    // console.log('PanelRocAucWidget', props)

    const { fieldConfig, data = {} } = props
    const { records = [] } = data
    const { data: formData } = fieldConfig ?? {}
    const { chartTitle: title, x, y } = formData ?? {}

    const rocAucData = useParseRocAuc({ records, x, y })
    const vizData = getRocAucConfig(title, [], rocAucData as any)

    return (
        <React.Suspense fallback={<BusyPlaceholder />}>
            <PlotlyVisualizer data={vizData} />
        </React.Suspense>
    )
}

const widget = new WidgetPlugin(PanelRocAucWidget, CONFIG)

export default widget