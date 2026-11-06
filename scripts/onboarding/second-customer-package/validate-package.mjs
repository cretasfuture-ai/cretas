#!/usr/bin/env node

import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOP_KEYS = new Set([
  'schemaVersion', 'generatedAgainstCommit', 'catalogVersion', 'catalogDigest',
  'targetFactoryId', 'status', 'payload', 'evidenceByJsonPointer', 'unresolved',
  'limitations', 'testOnly', 'canonicalDigest'
]);
const PAYLOAD_KEYS = new Set([
  'industryCode', 'industryName', 'schemas', 'suggestedData', 'overwrite',
  'draftCapabilityIntents', 'notBuilt'
]);
const DATA_KEYS = new Set([
  'productTypes', 'materialTypes', 'conversionRates', 'workProcesses',
  'haccpCheckpoints', 'modules', 'thresholds', 'warehouses', 'productionLines',
  'productProcesses', 'provisionDefaultUsers', 'codeSegments', 'alerts',
  'metricWatches', 'moduleConfigs', 'indicatorOptOut'
]);
const TYPE_ITEM_KEYS = new Set([
  'code', 'name', 'unit', 'description', 'storageType', 'productCategory',
  'level1Unit', 'boxConversionCoefficient', 'category'
]);
const MODULE_CONFIG_KEYS = new Set(['moduleId', 'moduleName', 'enabled', 'config']);
const PRODUCTION_LINE_KEYS = new Set(['name', 'capacities']);
const CAPACITY_KEYS = new Set(['productCode', 'capacityPerHour', 'capacityUnit']);
const PRODUCT_PROCESS_KEYS = new Set(['productCode', 'processNames', 'routeNodes', 'routeEdges']);
const ROUTE_NODE_KEYS = new Set(['id', 'label']);
const ROUTE_EDGE_KEYS = new Set(['fromNodeId', 'toNodeId', 'type']);
const PROCESS_KEYS = new Set([
  'name', 'category', 'sortOrder', 'unit', 'outputUnit', 'yieldMin', 'yieldMax',
  'outputKind', 'description', 'customFields', 'byproducts', 'auxMaterials',
  'packMaterials'
]);
const CUSTOM_FIELD_KEYS = new Set([
  'key', 'label', 'type', 'enabled', 'unit', 'standardValue', 'min', 'max'
]);
const MODULE_IDS = new Set([
  'production', 'warehouse', 'quality', 'scheduling', 'hr', 'equipment',
  'finance', 'smartbi', 'procurement', 'sales', 'logistics', 'trace', 'system'
]);
const DRAFT_INTENTS = new Set([
  'hygienic-zones', 'equipment-calibration', 'allergen-label', 'sanitation-cip',
  'environmental-monitoring', 'supplier-receiving', 'contract-material',
  'hazard-analysis', 'quality-release', 'deviation-capa', 'trace-recall',
  'emergency-defense'
]);
const ROUTE_TYPES = new Set(['FORWARD', 'BRANCH', 'MERGE', 'REWORK']);
const FORBIDDEN_KEY = /(password|passwd|secret|token|credential|authorization|createdby|appliedby|sourcefactoryid|attachment|sourcebusinessrows|api[_-]?key|privatekey|clientsecret|url|uri)/i;
const SOURCE_ID_KEY = /^(?:factoryid|ownercustomerid|customerid|supplierid|sourcefactoryid|id)$/i;
const URL_VALUE = /^https?:\/\//i;

export class PackageValidationError extends Error {
  constructor(errors) {
    super(errors.join('\n'));
    this.name = 'PackageValidationError';
    this.errors = errors;
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function addUnknown(errors, value, allowed, path) {
  if (!isObject(value)) return;
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) errors.push(`${path}: unknown property ${key}`);
  }
}

function assertObject(errors, value, path) {
  if (!isObject(value)) errors.push(`${path} must be an object`);
}

function assertArray(errors, value, path) {
  if (!Array.isArray(value)) errors.push(`${path} must be an array`);
}

function walkSafety(value, path, errors) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => walkSafety(item, `${path}[${index}]`, errors));
    return;
  }
  if (!isObject(value)) {
    if (typeof value === 'string' && URL_VALUE.test(value.trim())) {
      errors.push(`${path}: URLs are not allowed in an offline package`);
    }
    return;
  }
  for (const [key, item] of Object.entries(value)) {
    if (FORBIDDEN_KEY.test(key)) errors.push(`${path}.${key}: sensitive/business source field is forbidden`);
    const routeNodeId = key === 'id' && /\.routeNodes\[\d+\]$/.test(path);
    if (SOURCE_ID_KEY.test(key) && !routeNodeId && item != null) errors.push(`${path}.${key}: source business identifier is forbidden`);
    walkSafety(item, `${path}.${key}`, errors);
  }
}

function factPointers(value, path, pointers, skippedPaths = new Set()) {
  if (skippedPaths.has(path) || value == null || value === '') return;
  if (Array.isArray(value)) {
    value.forEach((item, index) => factPointers(item, `${path}/${index}`, pointers, skippedPaths));
    return;
  }
  if (isObject(value)) {
    const entries = Object.entries(value).filter(([, item]) => item != null && item !== '');
    if (!entries.length) return;
    for (const [key, item] of entries) factPointers(item, `${path}/${key}`, pointers, skippedPaths);
    return;
  }
  pointers.push(path);
}

function evidenceCovers(pointer, evidence) {
  return Object.entries(evidence).some(([candidate, entry]) => {
    const sourceRef = isObject(entry) && entry.status === 'CONFIRMED'
      && typeof entry.sourceRef === 'string' && entry.sourceRef.trim() !== '';
    return sourceRef && (pointer === candidate || pointer.startsWith(`${candidate}/`));
  });
}

function validateEvidence(value, errors) {
  if (!isObject(value)) return;
  for (const [pointer, entry] of Object.entries(value)) {
    const validPointer = typeof pointer === 'string' && pointer.startsWith('/')
      && pointer.split('/').slice(1).every((segment) => !/(^|[^~])~(?![01])/.test(segment));
    if (!validPointer) errors.push(`evidenceByJsonPointer.${pointer}: invalid JSON pointer`);
    if (!isObject(entry) || entry.status !== 'CONFIRMED'
      || typeof entry.sourceRef !== 'string' || entry.sourceRef.trim() === '') {
      errors.push(`evidenceByJsonPointer.${pointer}: requires status CONFIRMED and non-empty sourceRef`);
    }
  }
}

function numeric(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '' && /^[-+]?\d+(?:\.\d+)?$/.test(value.trim())) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function validateCustomFields(fields, path, errors) {
  if (fields == null) return;
  assertArray(errors, fields, path);
  if (!Array.isArray(fields)) return;
  const seen = new Set();
  fields.forEach((field, index) => {
    const at = `${path}[${index}]`;
    assertObject(errors, field, at);
    if (!isObject(field)) return;
    addUnknown(errors, field, CUSTOM_FIELD_KEYS, at);
    for (const required of ['key', 'label', 'type']) {
      if (typeof field[required] !== 'string' || field[required].trim() === '') errors.push(`${at}.${required}: required text`);
    }
    if (typeof field.key === 'string' && seen.has(field.key.trim())) errors.push(`${at}.key: duplicate key`);
    if (typeof field.key === 'string') seen.add(field.key.trim());
    if (!['number', 'text', 'date'].includes(field.type)) errors.push(`${at}.type: must be number, text, or date`);
    const standardKeys = ['unit', 'standardValue', 'min', 'max'].filter((key) => field[key] != null);
    if (standardKeys.length && field.type !== 'number') errors.push(`${at}: unit/standardValue/min/max require type number`);
    if (field.unit != null && (typeof field.unit !== 'string' || field.unit.trim().length > 20)) errors.push(`${at}.unit: must be text up to 20 characters`);
    const min = numeric(field.min);
    const max = numeric(field.max);
    const standard = numeric(field.standardValue);
    for (const key of ['standardValue', 'min', 'max']) {
      if (field[key] != null && numeric(field[key]) == null) errors.push(`${at}.${key}: must be numeric`);
    }
    if (min != null && max != null && min > max) errors.push(`${at}: min must be <= max`);
    if (standard != null && ((min != null && standard < min) || (max != null && standard > max))) errors.push(`${at}.standardValue: must be within min/max`);
  });
}

function validatePayload(doc, errors, unresolved) {
  const payload = doc.payload;
  assertObject(errors, payload, 'payload');
  if (!isObject(payload)) return;
  addUnknown(errors, payload, PAYLOAD_KEYS, 'payload');
  if (payload.schemas != null) {
    assertArray(errors, payload.schemas, 'payload.schemas');
    if (Array.isArray(payload.schemas)) payload.schemas.forEach((item, i) => assertObject(errors, item, `payload.schemas[${i}]`));
  }
  if (payload.draftCapabilityIntents != null) {
    assertArray(errors, payload.draftCapabilityIntents, 'payload.draftCapabilityIntents');
    if (Array.isArray(payload.draftCapabilityIntents)) payload.draftCapabilityIntents.forEach((id, i) => {
      if (!DRAFT_INTENTS.has(id)) errors.push(`payload.draftCapabilityIntents[${i}]: unknown capability id`);
    });
  }
  if (payload.notBuilt != null) {
    assertArray(errors, payload.notBuilt, 'payload.notBuilt');
    if (Array.isArray(payload.notBuilt)) payload.notBuilt.forEach((item, i) => {
      assertObject(errors, item, `payload.notBuilt[${i}]`);
      if (isObject(item)) addUnknown(errors, item, new Set(['group', 'item', 'why', 'howToFix', 'kind']), `payload.notBuilt[${i}]`);
    });
  }
  const data = payload.suggestedData;
  assertObject(errors, data, 'payload.suggestedData');
  if (!isObject(data)) return;
  addUnknown(errors, data, DATA_KEYS, 'payload.suggestedData');
  for (const listKey of ['productTypes', 'materialTypes', 'conversionRates', 'workProcesses', 'haccpCheckpoints', 'thresholds', 'warehouses', 'productionLines', 'productProcesses', 'codeSegments', 'alerts', 'metricWatches', 'moduleConfigs', 'indicatorOptOut']) {
    if (data[listKey] != null) assertArray(errors, data[listKey], `payload.suggestedData.${listKey}`);
  }
  if (data.modules != null && !isObject(data.modules)) errors.push('payload.suggestedData.modules must be an object');
  if (isObject(data.modules)) for (const [key, value] of Object.entries(data.modules)) {
    if (typeof value !== 'boolean') errors.push(`payload.suggestedData.modules.${key}: legacy module flag must be boolean`);
    unresolved.push(`payload.suggestedData.modules.${key}: legacy flag semantics require authoritative preview`);
  }
  if (data.provisionDefaultUsers !== false) errors.push('payload.suggestedData.provisionDefaultUsers: must be explicitly false for this package');
  if (payload.overwrite !== false) errors.push('payload.overwrite: must be explicitly false for this package');

  const products = new Set();
  const materials = new Set();
  for (const [key, set] of [['productTypes', products], ['materialTypes', materials]]) {
    if (!Array.isArray(data[key])) continue;
    data[key].forEach((item, i) => {
      const at = `payload.suggestedData.${key}[${i}]`;
      assertObject(errors, item, at);
      if (isObject(item)) {
        addUnknown(errors, item, TYPE_ITEM_KEYS, at);
        if (typeof item.code !== 'string' || item.code.trim() === '') errors.push(`${at}.code: required text`);
        if (set.has(item.code)) errors.push(`${at}.code: duplicate code`);
        set.add(item.code);
      }
    });
  }
  if (Array.isArray(data.workProcesses)) data.workProcesses.forEach((item, i) => {
    const at = `payload.suggestedData.workProcesses[${i}]`;
    assertObject(errors, item, at);
    if (!isObject(item)) return;
    addUnknown(errors, item, PROCESS_KEYS, at);
    if (item.customFields != null) validateCustomFields(item.customFields, `${at}.customFields`, errors);
  });
  if (Array.isArray(data.moduleConfigs)) data.moduleConfigs.forEach((item, i) => {
    const at = `payload.suggestedData.moduleConfigs[${i}]`;
    assertObject(errors, item, at);
    if (!isObject(item)) return;
    addUnknown(errors, item, MODULE_CONFIG_KEYS, at);
    if (item.enabled != null && typeof item.enabled !== 'boolean') errors.push(`${at}.enabled: must be boolean`);
    if (typeof item.moduleId !== 'string' || !MODULE_IDS.has(item.moduleId.trim().toLowerCase())) {
      errors.push(`${at}.moduleId: unknown offline module id; authoritative catalog preview required`);
    }
  });
  if (Array.isArray(data.productionLines)) data.productionLines.forEach((item, i) => {
    const at = `payload.suggestedData.productionLines[${i}]`;
    if (!isObject(item)) return;
    addUnknown(errors, item, PRODUCTION_LINE_KEYS, at);
    if (Array.isArray(item.capacities)) item.capacities.forEach((capacity, j) => {
      const capacityAt = `${at}.capacities[${j}]`;
      assertObject(errors, capacity, capacityAt);
      if (isObject(capacity)) addUnknown(errors, capacity, CAPACITY_KEYS, capacityAt);
    });
  });
  if (Array.isArray(data.conversionRates)) data.conversionRates.forEach((item, i) => {
    const at = `payload.suggestedData.conversionRates[${i}]`;
    if (!isObject(item)) return;
    if (!products.has(item.toCode) || !materials.has(item.fromCode)) errors.push(`${at}: fromCode/toCode must reference materialTypes/productTypes`);
  });
  if (Array.isArray(data.productProcesses)) data.productProcesses.forEach((item, i) => {
    const at = `payload.suggestedData.productProcesses[${i}]`;
    if (!isObject(item)) return;
    addUnknown(errors, item, PRODUCT_PROCESS_KEYS, at);
    if (!products.has(item.productCode)) errors.push(`${at}.productCode: orphan product reference`);
    const nodes = item.routeNodes;
    const nodeIds = new Set(Array.isArray(nodes) ? nodes.map((node) => node?.id) : []);
    if (Array.isArray(nodes)) nodes.forEach((node, j) => {
      if (isObject(node)) addUnknown(errors, node, ROUTE_NODE_KEYS, `${at}.routeNodes[${j}]`);
      if (!isObject(node) || typeof node.id !== 'string' || typeof node.label !== 'string') errors.push(`${at}.routeNodes[${j}]: id and label are required`);
    });
    if (Array.isArray(item.routeEdges)) item.routeEdges.forEach((edge, j) => {
      if (isObject(edge)) addUnknown(errors, edge, ROUTE_EDGE_KEYS, `${at}.routeEdges[${j}]`);
      if (!isObject(edge) || !nodeIds.has(edge.fromNodeId) || !nodeIds.has(edge.toNodeId)) errors.push(`${at}.routeEdges[${j}]: orphan node reference`);
      if (!isObject(edge) || !ROUTE_TYPES.has(edge.type)) errors.push(`${at}.routeEdges[${j}].type: unknown route type`);
    });
    if (Array.isArray(item.processNames) && Array.isArray(data.workProcesses)) {
      const processNames = new Set(data.workProcesses.map((process) => process?.name));
      item.processNames.forEach((name, j) => { if (!processNames.has(name)) errors.push(`${at}.processNames[${j}]: orphan work process reference`); });
    }
    if (Array.isArray(nodes) || Array.isArray(item.routeEdges)) unresolved.push(`${at}: route topology requires authoritative preview`);
  });
  if (Array.isArray(data.workProcesses)) data.workProcesses.forEach((process, i) => {
    if (typeof process?.unit === 'string' && process.unit.trim()) unresolved.push(`payload.suggestedData.workProcesses[${i}].unit: unit semantics require authoritative preview`);
    if (typeof process?.outputUnit === 'string' && process.outputUnit.trim()) unresolved.push(`payload.suggestedData.workProcesses[${i}].outputUnit: unit semantics require authoritative preview`);
  });
  for (const key of ['productTypes', 'materialTypes']) if (Array.isArray(data[key])) data[key].forEach((item, i) => {
    if (typeof item?.unit === 'string' && item.unit.trim()) unresolved.push(`payload.suggestedData.${key}[${i}].unit: unit semantics require authoritative preview`);
  });
  if (Array.isArray(data.productionLines)) data.productionLines.forEach((line, i) => {
    if (Array.isArray(line?.capacities)) line.capacities.forEach((capacity, j) => {
      if (typeof capacity?.capacityUnit === 'string' && capacity.capacityUnit.trim()) unresolved.push(`payload.suggestedData.productionLines[${i}].capacities[${j}].capacityUnit: unit semantics require authoritative preview`);
    });
  });
}

export function canonicalize(value) {
  return canonicalizeValue(value, true);
}

function canonicalizeValue(value, isRoot) {
  if (Array.isArray(value)) return `[${value.map((item) => canonicalizeValue(item, false)).join(',')}]`;
  if (isObject(value)) return `{${Object.keys(value)
    .filter((key) => !(isRoot && key === 'canonicalDigest'))
    .sort().map((key) => `${JSON.stringify(key)}:${canonicalizeValue(value[key], false)}`).join(',')}}`;
  return JSON.stringify(value);
}

export function canonicalDigest(value) {
  return createHash('sha256').update(canonicalize(value), 'utf8').digest('hex');
}

export function validatePackage(doc, { emitPreview = false } = {}) {
  const errors = [];
  const unresolved = Array.isArray(doc?.unresolved) ? [...doc.unresolved] : [];
  if (!isObject(doc)) throw new PackageValidationError(['package must be a JSON object']);
  addUnknown(errors, doc, TOP_KEYS, 'package');
  if (doc.schemaVersion !== '1.0') errors.push('schemaVersion must be "1.0"');
  if (!['UNCONFIRMED', 'CONFIRMED'].includes(doc.status)) errors.push('status must be UNCONFIRMED or CONFIRMED');
  if (!isObject(doc.evidenceByJsonPointer)) errors.push('evidenceByJsonPointer must be an object');
  if (!Array.isArray(doc.unresolved)) errors.push('unresolved must be an array');
  if (!Array.isArray(doc.limitations)) errors.push('limitations must be an array');
  if (doc.generatedAgainstCommit != null && !/^[0-9a-f]{40}$/i.test(doc.generatedAgainstCommit)) errors.push('generatedAgainstCommit must be a 40-hex commit hash when present');
  if (doc.catalogDigest != null && !/^[0-9a-f]{64}$/i.test(doc.catalogDigest)) errors.push('catalogDigest must be a 64-hex digest when present');
  if (doc.targetFactoryId != null) errors.push('targetFactoryId must remain null until server-side confirmation');
  validateEvidence(doc.evidenceByJsonPointer, errors);
  walkSafety(doc, 'package', errors);
  validatePayload(doc, errors, unresolved);
  if (doc.status === 'UNCONFIRMED' && !emitPreview) errors.push('status UNCONFIRMED refuses offline validation; collect evidence and confirm facts first');
  if (emitPreview) {
    if (doc.status !== 'CONFIRMED') errors.push('--emit-preview requires status CONFIRMED');
    if (!isObject(doc.evidenceByJsonPointer) || Object.keys(doc.evidenceByJsonPointer).length === 0) errors.push('--emit-preview requires source references in evidenceByJsonPointer');
    if (isObject(doc.evidenceByJsonPointer)) {
      const pointers = [];
      factPointers(doc.payload, '/payload', pointers, new Set(['/payload/overwrite', '/payload/suggestedData/provisionDefaultUsers']));
      for (const pointer of pointers) if (!evidenceCovers(pointer, doc.evidenceByJsonPointer)) errors.push(`${pointer}: confirmed fact has no source reference`);
      for (const pin of ['catalogVersion', 'catalogDigest']) {
        if (doc[pin] != null && !evidenceCovers(`/${pin}`, doc.evidenceByJsonPointer)) errors.push(`${pin}: catalog pin requires a source reference`);
      }
    }
    if (doc.catalogVersion == null || doc.catalogDigest == null) unresolved.push('catalog version/digest are not confirmed; authoritative catalog preview remains required');
  }
  if (errors.length) throw new PackageValidationError(errors);
  const result = JSON.parse(JSON.stringify(doc));
  result.unresolved = [...new Set(unresolved)];
  if (emitPreview) result.status = 'OFFLINE_PREVIEW_UNVERIFIED';
  result.canonicalDigest = canonicalDigest(result);
  return result;
}

async function main() {
  const args = process.argv.slice(2);
  const emitPreview = args.includes('--emit-preview');
  const json = args.includes('--json');
  const input = args.find((arg) => !arg.startsWith('--'));
  if (!input) {
    console.error('Usage: node validate-package.mjs <package.json> [--emit-preview] [--json]');
    process.exitCode = 2;
    return;
  }
  try {
    const doc = JSON.parse(await readFile(resolve(input), 'utf8'));
    const result = validatePackage(doc, { emitPreview });
    if (json || emitPreview) console.log(JSON.stringify(result, null, 2));
    else console.log(`VALID ${basename(input)} canonicalDigest=${result.canonicalDigest}`);
  } catch (error) {
    console.error(error instanceof PackageValidationError ? error.message : `ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) await main();
