import { watch } from 'vue'
import * as echarts from 'echarts'
import { state } from '../state'
const motion = () => ({ animation: state.motion !== 'off' && !matchMedia('(prefers-reduced-motion: reduce)').matches, animationDuration: state.motion === 'light' ? 100 : state.duration, animationDurationUpdate: state.motion === 'light' ? 100 : state.duration })
echarts.registerPreprocessor(option => Object.assign(option, motion()))
watch(() => [state.motion, state.duration], () => { for (const element of document.querySelectorAll<HTMLElement>('[_echarts_instance_]')) echarts.getInstanceByDom(element)?.setOption(motion()) }, { flush: 'post' })
