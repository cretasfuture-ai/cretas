import React, { useState } from 'react';
import { Text, View, Pressable } from 'react-native';
import { registerRootComponent } from 'expo';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { PhotoCanvas } from './QILabelQcReviewScreen';
import {
  addHumanAnnotation, removeDraftAnnotation, translateBoundingBox, updateDraftAnnotation,
  resizeBoundingBox, type LabelQcReviewPhotoDraft,
} from './labelQcReviewModel';

// Isolated, synthetic review data. No login, API writes, or human ground truth.
const initial: LabelQcReviewPhotoDraft = {
  photoId: 'local-fixture', reviewed: false,
  annotations: [{ key: 'human-1', source: 'HUMAN', bbox: { xMin: .3, yMin: .3, xMax: .55, yMax: .55 } }],
};

export function ReviewCanvasFixture() {
  const [draft, setDraft] = useState(initial);
  const [selected, setSelected] = useState<string | null>('human-1');
  const [viewport, setViewport] = useState({ scale: 1, translateX: 0, translateY: 0 });
  const [adds, setAdds] = useState(0);
  const [deletes, setDeletes] = useState(0);
  const [moves, setMoves] = useState(0);
  const [readOnly, setReadOnly] = useState(false);
  return <GestureHandlerRootView style={{ flex: 1, backgroundColor: '#edf2f0' }}>
    <Text>Local B04 review canvas</Text>
    <Text testID="fixture-state">{JSON.stringify({ count: draft.annotations.length, adds, deletes, moves, viewport, annotations: draft.annotations })}</Text>
    <View style={{ flexDirection: 'row', gap: 12 }}>
      <Pressable onPress={() => { setDraft(initial); setSelected('human-1'); setAdds(0); setDeletes(0); setMoves(0); setViewport({ scale: 1, translateX: 0, translateY: 0 }); }}><Text>Reset</Text></Pressable>
      <Pressable onPress={() => setViewport({ scale: 2, translateX: 15, translateY: 10 })}><Text>Zoom 2x</Text></Pressable>
      <Pressable onPress={() => setReadOnly(!readOnly)}><Text>Toggle readonly</Text></Pressable>
    </View>
    <View style={{ width: 390, height: 520 }}>
      <PhotoCanvas photo={{ id: 'local-fixture', attachmentId: 'local', orderIndex: 0, imageWidth: 390, imageHeight: 520, status: 'ANALYZED', annotations: [] }}
        draft={draft} referenceBoxes={[]} objectLabels={[]} selectedKey={selected} selectedObjectKey={null}
        viewport={viewport} readOnly={readOnly} onViewport={setViewport} onSelect={setSelected}
        onAdd={(x, y) => { setAdds(n => n + 1); const key = `added-${adds}`; setDraft(d => addHumanAnnotation(d, x, y, key)); setSelected(key); }}
        onDelete={key => { setDeletes(n => n + 1); setDraft(d => removeDraftAnnotation(d, key)); setSelected(null); }}
        onHumanLabel={(key, label) => setDraft(d => updateDraftAnnotation(d, key, { label }))}
        onMove={(key, x, y) => { setMoves(n => n + 1); setDraft(d => { const box = d.annotations.find(a => a.key === key)?.bbox; return box ? updateDraftAnnotation(d, key, { bbox: translateBoundingBox(box, x, y) }) : d; }); }}
        onResize={(key, x, y) => setDraft(d => { const box = d.annotations.find(a => a.key === key)?.bbox; return box ? updateDraftAnnotation(d, key, { bbox: resizeBoundingBox(box, x, y) }) : d; })}
        onObjectSelect={() => {}} onObjectMove={() => {}} onObjectResize={() => {}} />
    </View>
  </GestureHandlerRootView>;
}

registerRootComponent(ReviewCanvasFixture);
