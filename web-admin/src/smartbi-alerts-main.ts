import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import ui from '@nuxt/ui/vue-plugin';
import './styles/blacklake-prototype.css';
import TestEntry from './views/smart-bi/BlacklakeTestEntry.vue';
createApp(TestEntry, { mode: 'shared-alerts' }).use(createPinia()).use(ui).use(ElementPlus).mount('#app');
