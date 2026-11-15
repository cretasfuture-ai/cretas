import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const XLSX = require('xlsx')

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024
const STATE_DIR = 'private/fusion-upload-state'
const UPLOAD_PATH = /^\/api\/mobile\/F006\/smart-bi\/uploads(?:\/([^/]+))?(?:\/(fields|data|rename))?$/
const UPLOAD_STREAM_PATH = '/api/mobile/F006/smart-bi/upload-batch-stream'

function headerValue(headers, name) {
  if (!headers) return ''
  if (typeof headers.get === 'function') return headers.get(name) || ''
  const key = Object.keys(headers).find(k => k.toLowerCase() === name.toLowerCase())
  return key ? String(headers[key] || '') : ''
}

function safeSession(session) {
  const value = String(session || '')
  return /^[a-zA-Z0-9][a-zA-Z0-9_-]{7,95}$/.test(value) ? value : null
}

function statePath(root, session) {
  const safe = safeSession(session)
  if (!safe) throw new Error('无效本机会话')
  return path.join(root, STATE_DIR, `${safe}.json`)
}

function readState(root, session) {
  const file = statePath(root, session)
  if (!fs.existsSync(file)) return { version: 1, role: 'factory', factoryId: 'F006', uploads: [] }
  try {
    const value = JSON.parse(fs.readFileSync(file, 'utf8'))
    if (!value || value.role !== 'factory' || value.factoryId !== 'F006' || !Array.isArray(value.uploads)) {
      return { version: 1, role: 'factory', factoryId: 'F006', uploads: [] }
    }
    return value
  } catch {
    return { version: 1, role: 'factory', factoryId: 'F006', uploads: [] }
  }
}

function writeState(root, session, state) {
  const file = statePath(root, session)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const tmp = `${file}.${process.pid}.${Date.now()}.tmp`
  fs.writeFileSync(tmp, JSON.stringify({ ...state, updatedAt: new Date().toISOString() }, null, 2), 'utf8')
  fs.renameSync(tmp, file)
}

function response(send, res, status, data, type = 'application/json; charset=utf-8') {
  send(res, status, data, type)
}

function jsonError(send, res, status, message, code = 'FUSION_UPLOAD_ERROR') {
  response(send, res, status, { success: false, message, code })
}

function parseJsonBody(body) {
  if (!body || !body.length) return {}
  try { return JSON.parse(Buffer.from(body).toString('utf8')) || {} } catch { return {} }
}

function nonEmpty(value) {
  return value !== null && value !== undefined && value !== ''
}

function uniqueHeaders(sourceHeaders, columnCount) {
  const used = new Map()
  return Array.from({ length: columnCount }, (_, index) => {
    const sourceName = sourceHeaders[index]
    const base = nonEmpty(sourceName) ? String(sourceName) : `列${index + 1}`
    const seen = used.get(base) || 0
    used.set(base, seen + 1)
    return seen === 0 ? base : `${base}_${seen + 1}`
  })
}

function parseSheet(workbook, sheetName, sheetIndex) {
  const sheet = workbook.Sheets[sheetName]
  const matrix = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: false })
  const rows = matrix.map(row => Array.isArray(row) ? row : []).filter(row => row.some(nonEmpty))
  if (!rows.length) throw new Error(`Sheet「${sheetName}」为空，无法上传`)

  const headerIndex = rows.findIndex(row => row.some(nonEmpty))
  const sourceHeaders = rows[headerIndex]
  const columnCount = Math.max(sourceHeaders.length, ...rows.slice(headerIndex + 1).map(row => row.length), 0)
  if (!columnCount) throw new Error(`Sheet「${sheetName}」没有字段`)
  const sourceNames = Array.from({ length: columnCount }, (_, index) => {
    const value = sourceHeaders[index]
    return nonEmpty(value) ? String(value) : `列${index + 1}`
  })
  // API consumers use headers as object keys. Keep the source label on every
  // field, but make duplicate labels deterministic so no column is overwritten
  // when rows are converted from the persisted matrix to records.
  const headers = uniqueHeaders(sourceNames, columnCount)
  const data = rows.slice(headerIndex + 1).map(row => Array.from({ length: columnCount }, (_, index) => row[index] === undefined ? null : row[index]))
  const fields = headers.map((name, index) => {
    const sourceName = sourceNames[index]
    const values = data.map(row => row[index]).filter(nonEmpty)
    const numeric = values.length > 0 && values.every(value => typeof value === 'number' || (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))))
    return {
      originalName: sourceName,
      sourceOriginalName: sourceName,
      displayName: name,
      standardName: name,
      fieldType: numeric ? 'NUMERIC' : 'TEXT',
      semanticType: numeric ? 'measure' : 'dimension',
      chartRole: numeric ? 'y_axis' : 'x_axis',
      isDimension: !numeric,
      isMeasure: numeric,
      isTime: /日期|时间|date|time/i.test(name),
      formatPattern: null,
      columnIndex: index,
    }
  })
  return {
    sheetIndex,
    sheetName,
    headers,
    data,
    fields,
    rowCount: data.length,
    columnCount,
    tableType: 'LOCAL_EXCEL',
  }
}

function parseWorkbook(buffer, fileName = '') {
  const lowerName = String(fileName).toLowerCase()
  if (lowerName.endsWith('.xlsx') && !(buffer.length >= 4 && buffer.subarray(0, 2).toString('ascii') === 'PK')) {
    throw new Error('Excel 文件损坏或格式不受支持：xlsx 文件不是有效的 ZIP 工作簿')
  }
  if (lowerName.endsWith('.xls') && !(buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from('d0cf11e0a1b11ae1', 'hex')))) {
    throw new Error('Excel 文件损坏或格式不受支持：xls 文件不是有效的工作簿')
  }
  let workbook
  try {
    workbook = XLSX.read(buffer, { type: 'buffer', raw: true, cellDates: true })
  } catch (error) {
    throw new Error(`Excel 文件损坏或格式不受支持：${error instanceof Error ? error.message : '解析失败'}`)
  }
  const names = Array.isArray(workbook.SheetNames) ? workbook.SheetNames : []
  if (!names.length) throw new Error('Excel 文件没有 Sheet')
  const parsed=[];for(const[name,index]of names.map((name,index)=>[name,index])){try{const sheet=parseSheet(workbook,name,index);if(sheet.rowCount>0)parsed.push(sheet)}catch(error){if(!String(error.message).includes('为空'))throw error}}if(!parsed.length)throw new Error('工作簿没有可分析的数据行');return parsed
}

function nextUploadId(state) {
  const used = new Set(state.uploads.map(upload => String(upload.id)))
  let id = Date.now() * 100 + 1
  while (used.has(String(id))) id += 1
  return id
}

function historyItem(upload) {
  return {
    id: upload.id,
    fileName: upload.fileName,
    originalFileName: upload.fileName,
    sheetName: upload.sheetName,
    tableType: upload.tableType,
    rowCount: upload.rowCount,
    columnCount: upload.columnCount,
    status: 'COMPLETED',
    createdAt: upload.createdAt,
  }
}

function localUploads(root, session) {
  return readState(root, session).uploads
}

function snapshotResponses(root) {
  const file = path.join(root, 'private', 'fusion-responses.json')
  if (!fs.existsSync(file)) return {}
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) || {} } catch { return {} }
}

function snapshotResponse(root, target) {
  const responses = snapshotResponses(root)
  const exact = responses[`GET ${target}`]
  if (exact) return exact
  const targetUrl = new URL(target, 'http://fusion.local')
  const candidates = Object.entries(responses).filter(([key]) => {
    if (!key.startsWith('GET ')) return false
    try {
      const url = new URL(key.slice(4), 'http://fusion.local')
      return url.pathname === targetUrl.pathname
    } catch { return false }
  })
  if (!candidates.length) return null
  if (targetUrl.pathname.endsWith('/data')) {
    const requestedSize = Number(targetUrl.searchParams.get('size') || 0)
    const requestedPage = Number(targetUrl.searchParams.get('page') || 0)
    const matching = candidates.find(([, value]) => {
      const key = candidates.find(([candidateKey, candidateValue]) => candidateValue === value)?.[0]
      if (!key) return false
      const url = new URL(key.slice(4), 'http://fusion.local')
      return Number(url.searchParams.get('size') || 0) === requestedSize && Number(url.searchParams.get('page') || 0) === requestedPage
    })
    return matching?.[1] || candidates[0][1]
  }
  return candidates[0][1]
}

function localDataResponse(upload, targetUrl) {
  const page = Math.max(0, Number(targetUrl.searchParams.get('page') || 0) || 0)
  const size = Math.max(1, Math.min(5000, Number(targetUrl.searchParams.get('size') || 50) || 50))
  const start = page * size
  const data = upload.data.slice(start, start + size).map(row => Object.fromEntries(upload.headers.map((header, index) => [header, row[index] === undefined ? null : row[index]])))
  return {
    success: true,
    data: {
      headers: upload.headers,
      data,
      total: upload.data.length,
      page,
      size,
      totalPages: Math.ceil(upload.data.length / size),
    },
    prototypeLocalOnly: true,
  }
}

function sse(events) {
  return events.map(event => `data:${JSON.stringify(event)}\n\n`).join('')
}

function requestFileFromMultipart(body, contentType) {
  if (!contentType.toLowerCase().includes('multipart/form-data')) throw new Error('上传请求缺少 multipart/form-data')
  return new Request('http://fusion.local/upload', { method: 'POST', headers: { 'content-type': contentType }, body: Buffer.from(body || []) }).formData()
}

async function handleUpload({ root, session, body, headers, send, res }) {
  const buffer = Buffer.from(body || [])
  if (buffer.length > MAX_UPLOAD_BYTES) { jsonError(send, res, 413, '样板上传上限为50MB', 'UPLOAD_TOO_LARGE'); return true }
  let form
  try { form = await requestFileFromMultipart(buffer, headerValue(headers, 'content-type')) } catch (error) {
    jsonError(send, res, 400, error instanceof Error ? error.message : '上传文件读取失败', 'INVALID_MULTIPART')
    return true
  }
  const entry = form.get('file') || form.get('upload')
  if (!entry || typeof entry.arrayBuffer !== 'function') { jsonError(send, res, 400, '请选择 Excel 文件', 'FILE_REQUIRED'); return true }
  const fileName = typeof entry.name === 'string' && entry.name ? entry.name : '上传文件.xlsx'
  let sheets
  try { sheets = parseWorkbook(Buffer.from(await entry.arrayBuffer()), fileName) } catch (error) {
    jsonError(send, res, 400, error instanceof Error ? error.message : 'Excel 文件解析失败', 'INVALID_EXCEL')
    return true
  }
  const createdAt = new Date().toISOString()
  const state = readState(root, session)
  let uploadId = nextUploadId(state)
  const records = sheets.map(sheet => {
    const record = { id: uploadId++, fileName, ...sheet, createdAt, role: 'factory', factoryId: 'F006' }
    return record
  })
  // Commit only after the entire workbook has parsed successfully. Failed files
  // therefore cannot create a source hash or a partially visible history row.
  state.uploads.push(...records)
  writeState(root, session, state)
  const results = records.map(record => ({
    success: true,
    message: `已读取 ${record.rowCount} 行实际数据`,
    sheetIndex: record.sheetIndex,
    sheetName: record.sheetName,
    uploadId: record.id,
    fileName: record.fileName,
    savedRows: record.rowCount,
    detectedDataType: record.tableType,
    headers: record.headers,
    rowCount: record.rowCount,
    columnCount: record.columnCount,
    flowResult: { kpiSummary: { rowCount: record.rowCount, columnCount: record.columnCount }, charts: [] },
  }))
  const totalRows = records.reduce((sum, record) => sum + record.rowCount, 0)
  const result = { success: true, fileName, totalSheets: records.length, successCount:records.length,failedCount:0,requiresConfirmationCount:0,totalSavedRows: totalRows, results, message: '本机 Excel 上传解析完成', prototypeLocalOnly: true }
  const events = [{ type: 'PREPARING', progress: 5, totalSheets: records.length, message: '正在读取 Excel 文件...' }]
  for (const record of records) {
    events.push({ type: 'SHEET_START', progress: 15, sheetIndex: record.sheetIndex, sheetName: record.sheetName, stage: '解析中', totalSheets: records.length, message: `正在解析 ${record.sheetName}` })
    events.push({ type: 'PARSING', progress: 65, sheetIndex: record.sheetIndex, sheetName: record.sheetName, stage: '解析完成', completedSheets: record.sheetIndex, totalSheets: records.length, message: `${record.rowCount} 行已读取` })
    events.push({ type: 'SHEET_COMPLETE', progress: Math.round(70 + ((record.sheetIndex + 1) / records.length) * 20), sheetIndex: record.sheetIndex, sheetName: record.sheetName, stage: '完成', completedSheets: record.sheetIndex + 1, totalSheets: records.length })
  }
  events.push({ type: 'COMPLETE', progress: 100, completedSheets: records.length, totalSheets: records.length, result })
  response(send, res, 200, sse(events), 'text/event-stream; charset=utf-8')
  return true
}

function findUpload(root, session, id) {
  const key = String(id)
  return localUploads(root, session).find(upload => String(upload.id) === key) || null
}

function sendSnapshot(send, res, snapshot) {
  if (!snapshot) return false
  response(send, res, snapshot.status || 200, snapshot.body || snapshot, snapshot.type || 'application/json; charset=utf-8')
  return true
}

export function listFusionUploads(root, session) {
  return localUploads(root, session).map(historyItem).sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt)))
}

export function getFusionUploadTable(root, session, id) {
  const upload = findUpload(root, session, id)
  const data = upload ? upload.data.map(row => Object.fromEntries(upload.headers.map((header, index) => [header, row[index] === undefined ? null : row[index]]))) : null
  return upload ? { headers: upload.headers, data, total: data.length, page: 0, size: data.length, totalPages: data.length ? 1 : 0 } : null
}

export async function handleFusionUpload({ parent, root, role, session, method, target, body, headers, send, res }) {
  if (role !== 'factory' || !safeSession(session) || !String(target || '').startsWith('/api/mobile/F006/smart-bi/')) return false
  const targetUrl = new URL(target, 'http://fusion.local')
  const pathname = targetUrl.pathname
  if(method==='POST'&&pathname==='/api/mobile/F006/smart-bi/sheets'){
    try{const form=await requestFileFromMultipart(body,headerValue(headers,'content-type')),file=form.get('file');if(!file||typeof file.arrayBuffer!=='function')throw Error('请选择 Excel 文件');if(file.size>MAX_UPLOAD_BYTES)throw Error('文件超过50MB');const buffer=Buffer.from(await file.arrayBuffer()),book=XLSX.read(buffer,{type:'buffer',raw:true,cellDates:true}),sheets=book.SheetNames.map((name,index)=>{const matrix=XLSX.utils.sheet_to_json(book.Sheets[name],{header:1,raw:true,defval:null,blankrows:false});return {index,name,rowCount:Math.max(0,matrix.length-1),columnCount:Math.max(0,...matrix.map(row=>row.length)),preview:matrix.slice(0,6)}});if(!sheets.length)throw Error('文件没有工作表');response(send,res,200,{success:true,data:sheets,prototypeLocalOnly:true});return true}catch(error){jsonError(send,res,422,error.message,'WORKBOOK_PREVIEW_FAILED');return true}
  }
  if (method === 'POST' && pathname === UPLOAD_STREAM_PATH) return handleUpload({ root, session, body, headers, send, res })
  const match = pathname.match(UPLOAD_PATH)
  if (!match) return false
  const id = match[1]
  const operation = match[2]
  if (method === 'GET' && !id) {
    const stateItems = listFusionUploads(root, session)
    const state = readState(root,session)
    const snapshot = snapshotResponse(root, `${pathname}?page=0&size=200`)
    const oldItems = snapshot?.body?.data?.content || snapshot?.body?.data || []
    const merged = [...stateItems, ...(Array.isArray(oldItems) ? oldItems.filter(item => !stateItems.some(local => String(local.id) === String(item.id))) : [])].filter(item=>!(state.hiddenIds||[]).includes(String(item.id))).map(item=>({...item,...state.historyOverrides?.[String(item.id)]}))
    const page = Math.max(0, Number(targetUrl.searchParams.get('page') || 0) || 0)
    const size = Math.max(1, Number(targetUrl.searchParams.get('size') || 200) || 200)
    response(send, res, 200, { success: true, data: { content: merged.slice(page * size, page * size + size), totalElements: merged.length, totalPages: Math.ceil(merged.length / size), number: page, size }, prototypeLocalOnly: true })
    return true
  }
  if (!id) return false
  const stateNow=readState(root,session)
  if((stateNow.hiddenIds||[]).includes(String(id))){jsonError(send,res,404,'该本机上传记录已删除','LOCAL_UPLOAD_DELETED');return true}
  const upload = findUpload(root, session, id)
  if (method === 'GET' && upload && operation === 'fields') { response(send, res, 200, { success: true, data: upload.fields, prototypeLocalOnly: true }); return true }
  if (method === 'GET' && upload && operation === 'data') { response(send, res, 200, localDataResponse(upload, targetUrl)); return true }
  if (method === 'GET' && upload && !operation) { response(send, res, 200, { success: true, data: historyItem(upload), prototypeLocalOnly: true }); return true }
  if (method === 'GET' && operation === 'data') return false
  if (method === 'GET') return sendSnapshot(send, res, snapshotResponse(root, target))
  if (method === 'DELETE' && upload && !operation) {
    const state = readState(root, session); state.uploads = state.uploads.filter(item => String(item.id) !== String(id)); writeState(root, session, state)
    response(send, res, 200, { success: true, data: null, message: '已删除本机上传', prototypeLocalOnly: true }); return true
  }
  if(method==='DELETE'&&!upload&&!operation){const captured=snapshotResponse(root,'/api/mobile/F006/smart-bi/uploads?page=0&size=200'),items=captured?.body?.data?.content||[];if(items.some(item=>String(item.id)===String(id))){const state=readState(root,session);state.hiddenIds=[...new Set([...(state.hiddenIds||[]),String(id)])];writeState(root,session,state);response(send,res,200,{success:true,data:null,prototypeLocalOnly:true});return true}}
  if ((method === 'PATCH' || method === 'PUT' || method === 'POST') && upload && (!operation || operation === 'rename')) {
    const request = parseJsonBody(body); const name = String(request.fileName || request.name || request.originalFileName || '').trim()
    if (!name) { jsonError(send, res, 400, '缺少新文件名', 'RENAME_NAME_REQUIRED'); return true }
    const state = readState(root, session); state.uploads = state.uploads.map(item => String(item.id) === String(id) ? { ...item, fileName: name } : item); writeState(root, session, state)
    response(send, res, 200, { success: true, data: historyItem({ ...upload, fileName: name }), message: '已重命名本机上传', prototypeLocalOnly: true }); return true
  }
  return false
}

