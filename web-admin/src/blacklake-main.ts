// Isolated local test entry: real Java login + the same SmartBI page/API.
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import BlacklakeTestEntry from './views/smart-bi/BlacklakeTestEntry.vue';

createApp(BlacklakeTestEntry).use(createPinia()).use(ElementPlus).mount('#app');
