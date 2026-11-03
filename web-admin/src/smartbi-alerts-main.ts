import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import TestEntry from './views/smart-bi/BlacklakeTestEntry.vue';
createApp(TestEntry, { mode: 'shared-alerts' }).use(createPinia()).use(ElementPlus).mount('#app');
