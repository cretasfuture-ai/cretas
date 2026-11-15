<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import UTooltip from '@nuxt/ui/components/Tooltip.vue'
import type { NuxtAction } from './types'

const props = defineProps<{ regions: Array<{ id: string; actions: NuxtAction[] }> }>()
const emit = defineEmits<{ invoke: [action: NuxtAction] }>()
const present = ref<Record<string, boolean>>({})
let observer: MutationObserver | undefined

function scan(): void {
  const next: Record<string, boolean> = {}
  for (const region of props.regions) next[region.id] = Boolean(document.getElementById(region.id))
  present.value = next
}
function invoke(action: NuxtAction): void {
  if (action.disabled || action.loading) return
  emit('invoke', action)
}
watch(() => props.regions.map(r=>r.id).join('|'),scan,{flush:'post'})
onMounted(() => {
  scan()
  observer = new MutationObserver(scan)
  observer.observe(document.body, { childList: true, subtree: true })
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <template v-for="region in props.regions" :key="region.id">
    <Teleport v-if="present[region.id]" :to="`#${region.id}`">
      <div class="nuxt-supply-actions" :data-nuxt-region-id="region.id">
        <template v-for="action in region.actions" :key="action.id">
          <UTooltip v-if="action.help" :ui="{content:'cretas-nuxt-surface'}" :text="action.help">
            <UButton :label="action.label" :aria-label="action.ariaLabel || action.label" :disabled="action.disabled" :loading="action.loading" :color="action.danger ? 'error' : action.primary ? 'primary' : 'neutral'" :variant="action.kind === 'row' ? 'ghost' : action.primary ? 'solid' : 'outline'" :size="action.kind === 'row' ? 'xs' : 'sm'" @click="invoke(action)" />
          </UTooltip>
          <UButton v-else :label="action.label" :aria-label="action.ariaLabel || action.label" :disabled="action.disabled" :loading="action.loading" :color="action.danger ? 'error' : action.primary ? 'primary' : 'neutral'" :variant="action.kind === 'row' ? 'ghost' : action.primary ? 'solid' : 'outline'" :size="action.kind === 'row' ? 'xs' : 'sm'" @click="invoke(action)" />
        </template>
      </div>
    </Teleport>
  </template>
</template>
