import { useEffect } from 'react'
import { StoreApi } from 'zustand'
import { useStoreApi } from '../hooks/useStore'
import { WidgetStateT, WidgetStoreState } from '@starwhale/core/types'

type StoreUpdaterProps = {
    onStateChange: (param: WidgetStateT) => void
}

export function useStoreUpdater<T>(value: T | undefined, setStoreState: (param: T) => void) {
    useEffect(() => {
        if (typeof value !== 'undefined') {
            setStoreState(value)
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [value])
}

// updates with values in store that don't have a dedicated setter function
export function useDirectStoreUpdater(
    key: keyof WidgetStoreState,
    value: unknown,
    setState: StoreApi<WidgetStoreState>['setState']
) {
    useEffect(() => {
        if (typeof value !== 'undefined') {
            // eslint-disable-next-line no-console
            // console.log('set state', key)
            setState({ [key]: value }, false)
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [value])
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
const selector = (s: WidgetStoreState) => ({
    // initState: s.initState,
})

const StoreUpdater = ({ onStateChange }: StoreUpdaterProps) => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    // const { reset } = useStore(selector, shallow)
    const store = useStoreApi()

    // useEffect(() => {
    //     return () => {
    //         // reset()
    //     }
    // }, [reset])

    useDirectStoreUpdater('onStateChange', onStateChange, store.setState)
    // useStoreUpdater<WidgetStateT>(state, initState)
    return null
}

export { StoreUpdater }

export default StoreUpdater