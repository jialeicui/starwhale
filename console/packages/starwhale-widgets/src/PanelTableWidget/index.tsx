import React from 'react'
import { WidgetRendererProps, WidgetConfig, WidgetGroupType } from '@starwhale/core/types'
import { WidgetPlugin } from '@starwhale/core/widget'
import PanelTable from './component/Table'

export const CONFIG: WidgetConfig = {
    type: 'ui:panel:table',
    group: WidgetGroupType.PANEL,
    name: 'Table',
    fieldConfig: {
        uiSchema: {},
        schema: {},
    },
}

function PanelTableWidget(props: WidgetRendererProps<any, any>) {
    // console.log('PanelTableWidget', props)

    const { data = {} } = props
    const { columnTypes = [], records = [] } = data

    const columns = React.useMemo(() => {
        return columnTypes.map((column: any) => column.name)?.sort((a: string) => (a === 'id' ? -1 : 1)) ?? []
    }, [columnTypes])

    const $data = React.useMemo(() => {
        if (!records) return []

        return (
            records.map((item: any) => {
                return columns.map((k: string) => item?.[k])
            }) ?? []
        )
    }, [records, columns])

    return (
        <div style={{ width: '100%', height: '100%' }}>
            <PanelTable columns={columns} data={$data} />
        </div>
    )
}

const widget = new WidgetPlugin(PanelTableWidget, CONFIG)

export default widget