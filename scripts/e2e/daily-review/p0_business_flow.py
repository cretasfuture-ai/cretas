"""Resumable, loopback-only business acceptance. No SQL business writes or fixtures."""
from __future__ import annotations

import argparse
from datetime import date
import json
import hashlib
from pathlib import Path
from urllib.parse import urlsplit

import requests


class BusinessFlow:
    def __init__(self, url: str, credentials: Path, out: Path, run_key: str = "normal"):
        if run_key not in ("normal", "verified"):
            raise ValueError("Unknown acceptance run key")
        self.run_key = run_key
        target = urlsplit(url)
        if target.scheme != 'http' or target.hostname != '127.0.0.1' or target.port != 10060 or target.path not in ('', '/') or target.username or target.password or target.query or target.fragment:
            raise ValueError('Only the owned loopback Java test service 127.0.0.1:10060 is allowed')
        self.base = url.rstrip('/')
        self.factory = 'VISION-ENTRY-A'
        self.out = out.resolve()
        self.out.mkdir(parents=True, exist_ok=True)
        self.state_path = self.out / 'business-state.json'
        self.state = json.loads(self.state_path.read_text(encoding='utf-8')) if self.state_path.exists() else {'factoryId': self.factory, 'entities': {}, 'events': []}
        if self.state['factoryId'] != self.factory:
            raise ValueError('Saved acceptance belongs to another factory')
        accounts = json.loads(credentials.read_text(encoding='utf-8'))
        self.http = requests.Session()
        response = self.http.post(self.base + '/api/mobile/auth/unified-login', json={'username': 'daily_factory_a', 'password': accounts['password'], 'clientType': 'web'}, timeout=30)
        response.raise_for_status()
        body = response.json()
        if not body.get('success'):
            raise RuntimeError(body.get('message'))
        data = body['data']
        actor = data
        if actor.get('factoryId') != self.factory or actor.get('username') != 'daily_factory_a' or actor.get('role') != 'factory_super_admin':
            raise ValueError('Explicit factory user login proof missing: ' + str(list(data)))
        self.actor_id = actor['userId']
        self.http.headers['Authorization'] = 'Bearer ' + (data.get('token') or data['accessToken'])
        self.state['identity'] = {k: actor.get(k) for k in ['userId', 'username', 'factoryId', 'role']}
        self.persist()

    def persist(self):
        self.state_path.write_text(json.dumps(self.state, ensure_ascii=False, indent=2), encoding='utf-8')

    def call(self, method: str, path: str, payload=None):
        if not path.startswith('/') or '..' in path or '://' in path:
            raise ValueError('Factory-relative business path required')
        response = self.http.request(method, self.base + '/api/mobile/' + self.factory + path, json=payload, timeout=60)
        try:
            body = response.json()
        except ValueError:
            body = {'success': False, 'message': 'Non-JSON response'}
        event = {'method': method, 'path': path, 'httpStatus': response.status_code, 'success': body.get('success'), 'message': body.get('message')}
        self.state['events'].append(event)
        self.persist()
        if response.status_code >= 400 or not body.get('success'):
            raise RuntimeError(json.dumps(event, ensure_ascii=False))
        return body.get('data')

    def create(self, name: str, path: str, payload):
        if name in self.state['entities']:
            return self.state['entities'][name]
        data = self.call('POST', path, payload)
        self.state['entities'][name] = data
        self.persist()
        print(json.dumps({'created': name, 'id': data.get('id') if isinstance(data, dict) else None}, ensure_ascii=False), flush=True)
        return data

    def master_data(self):
        self.create('supplier', '/suppliers', {'name': 'P0合成体验供应商', 'supplierCode': 'P0-LOCAL-SUPPLIER', 'contactPerson': '本地合成测试联系人', 'phone': '13800009901', 'address': '本地测试数据，请勿用于真实交易', 'notes': 'P0本地闭环合成样本'})
        self.create('raw', '/raw-material-types', {'name': 'P0合成体验原料', 'code': 'P0-LOCAL-RAW', 'category': '肉类', 'unit': 'kg', 'storageType': 'frozen', 'shelfLifeDays': 180, 'isActive': True})
        self.create('supplier_material', '/suppliers/' + self.state['entities']['supplier']['id'] + '/materials', {'materialTypeId': self.state['entities']['raw']['id'], 'defaultPurchasePrice': 10, 'purchaseUnit': 'kg', 'currency': 'CNY', 'active': True})
        for key, name, unit, grams in [('a', 'P0体验卤制品A（散装）', 'kg', 1000), ('b', 'P0体验卤制品B（500克盒装）', '盒', 500)]:
            self.create('sku_' + key, '/product-types', {'code': 'P0-LOCAL-SKU-' + key.upper(), 'name': name, 'unit': unit, 'netContentQuantity': grams, 'netContentUnit': 'g', 'shelfLifeDays': 30, 'category': '本地体验', 'productCategory': 'FINISHED_PRODUCT', 'isActive': True})

    def purchase(self):
        supplier, raw = (self.state['entities'][key] for key in ['supplier', 'raw'])
        order = self.create('purchase_order', '/purchase/orders', {'supplierId': supplier['id'], 'orderDate': date.today().isoformat(), 'expectedDeliveryDate': date.today().isoformat(), 'remark': 'P0本地正常收料闭环（合成样本）', 'items': [{'materialTypeId': raw['id'], 'materialName': raw['name'], 'quantity': 100, 'unit': 'kg', 'quantityUnit': 'kg', 'unitPrice': 10, 'priceUnit': 'kg', 'taxRate': 0}]})
        if 'purchase_submitted' not in self.state['entities']:
            result = self.call('POST', '/purchase/orders/' + order['id'] + '/submit', {})
            self.state['entities']['purchase_submitted'] = result
            self.persist()
            print(json.dumps({'orderId': order['id'], 'submittedStatus': result.get('status')}, ensure_ascii=False))
        tasks = self.call('GET', '/warehouse/receiving/tasks')
        (self.out / 'receiving-tasks.json').write_text(json.dumps(tasks, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'receivingTasks': len(tasks)}, ensure_ascii=False))

    def receipt_readback(self):
        order = self.state['entities']['purchase_order']
        receipts = self.call('GET', '/warehouse/receiving/receipts/by-order/' + order['id'])
        self.state['entities']['receipts'] = receipts
        self.persist()
        print(json.dumps(receipts, ensure_ascii=False))

    def workflows(self):
        raw = self.state['entities']['raw']
        packaging = self.create('packaging', '/raw-material-types', {'name': 'P0体验食品包装袋盒', 'code': 'P0-LOCAL-PACK', 'category': '包材', 'unit': '个', 'storageType': 'dry', 'shelfLifeDays': 365, 'taxIncludedUnitPrice': 0.5, 'taxRate': 'TAX_13', 'isActive': True})
        for key in ['a', 'b']:
            sku = self.state['entities']['sku_' + key]
            process = self.create('process_' + key, '/work-processes', {
                'processName': 'P0体验熟制包装' + key.upper(), 'processCategory': 'P0本地体验',
                'createCategory': True, 'standardHourlyRate': 20, 'needsInput': True,
                'isActive': True, 'defaultOutputMaterialKind': 'FINISHED_GOOD'})
            draft_name = 'workflow_draft_' + key
            if draft_name not in self.state['entities']:
                def material(node_id, kind, item, x, unit):
                    return {'id': node_id, 'kind': kind, 'position': {'x': x, 'y': 0},
                            'data': {'name': item['name'], 'skuId': item['id'], 'skuCode': item.get('code', item['id']), 'unit': unit}}
                ports = [{'id': 'in-raw', 'direction': 'INPUT', 'materialNodeId': 'raw', 'materialKind': 'RAW_MATERIAL', 'unit': 'kg', 'ordinal': 0},
                         {'id': 'out-fg', 'direction': 'OUTPUT', 'materialNodeId': 'finished', 'materialKind': 'FINISHED_GOOD', 'unit': sku['unit'], 'ordinal': 0}]
                draft = {'schemaVersion': 1, 'nodes': [material('raw', 'RAW_MATERIAL', raw, 0, 'kg'),
                    {'id': 'cook', 'kind': 'PROCESS', 'position': {'x': 260, 'y': 0}, 'data': {
                        'workProcessId': process['id'], 'processName': process['processName'],
                        'reportingSelectionMode': 'ACTUAL_IO', 'inputUnit': 'kg', 'outputUnit': sku['unit'],
                        'ports': ports, 'conversionRule': {'mode': 'ACTUAL_WEIGHT'}}},
                    material('finished', 'FINISHED_GOOD', sku, 520, sku['unit'])],
                    'edges': [{'id': 'raw-cook', 'source': 'raw', 'sourceHandle': 'output', 'target': 'cook', 'targetHandle': 'in-raw'},
                              {'id': 'cook-fg', 'source': 'cook', 'sourceHandle': 'out-fg', 'target': 'finished', 'targetHandle': 'input'}],
                    'viewport': {'x': 0, 'y': 0, 'zoom': 1}}
                self.state['entities'][draft_name] = self.call('PUT', '/product-process-workflows/' + sku['id'] + '/draft', draft)
                self.persist()
            draft = self.state['entities'][draft_name]
            if 'workflow_published_' + key not in self.state['entities'] and any('baseUnit' not in n.get('data', {}) for n in draft['nodes'] if n['kind'] != 'PROCESS'):
                for node in draft['nodes']:
                    if node['kind'] != 'PROCESS':
                        node['data']['baseUnit'] = node['data'].pop('unit')
                        node['data']['bound'] = True
                draft = self.call('PUT', '/product-process-workflows/' + sku['id'] + '/draft', draft)
                self.state['entities'][draft_name] = draft
                self.persist()
            self.create('bom_' + key, '/bom/recipes/ensure-draft', {'productTypeId': sku['id'], 'workflowRevisionId': draft.get('revisionId')})
            self.create('bom_pack_' + key, '/bom/recipes/' + self.state['entities']['bom_' + key]['id'] + '/items', {'materialTypeId': packaging['id'], 'standardQuantity': 1, 'unit': '个', 'materialCategory': 'PACKAGING', 'packagingRole': 'PRIMARY_CONTAINER', 'naturalQuantity': 1})
            self.create('workflow_published_' + key, '/product-process-workflows/' + sku['id'] + '/publish', {'lockVersion': draft['lockVersion']})

    def prepare_stock(self):
        self.create('logistics_warehouse', '/factory/warehouses', {'code': 'WH-LOG', 'name': 'P0体验物流仓', 'type': 'LOGISTICS'})
        self.create('workshop', '/factory/warehouses', {'code': 'WH-WKS', 'name': 'P0体验生产仓', 'type': 'WORKSHOP'})
        self.create('finished_warehouse', '/factory/warehouses', {'code': 'WH-FG', 'name': 'P0体验成品仓', 'type': 'FINISHED'})
        supplier, packaging = (self.state['entities'][name] for name in ['supplier', 'packaging'])
        self.create('pack_supplier_material', '/suppliers/' + supplier['id'] + '/materials', {'materialTypeId': packaging['id'], 'defaultPurchasePrice': 0.5, 'purchaseUnit': '个', 'currency': 'CNY', 'active': True})
        order = self.create('pack_po', '/purchase/orders', {'supplierId': supplier['id'], 'orderDate': date.today().isoformat(), 'expectedDeliveryDate': date.today().isoformat(), 'remark': 'P0包材正常采购入库', 'items': [{'materialTypeId': packaging['id'], 'quantity': 100, 'unit': '个', 'quantityUnit': '个', 'unitPrice': 0.5, 'priceUnit': '个', 'taxRate': 0}]})
        self.create('pack_po_submitted', '/purchase/orders/' + order['id'] + '/submit', {})
        order = self.call('GET', '/purchase/orders/' + order['id'])
        receipt = self.create('pack_receipt', '/warehouse/receiving/receipts', {'purchaseOrderId': order['id'], 'supplierId': supplier['id'], 'receiveDate': date.today().isoformat(), 'warehouseId': 'REVIEW-TRACE-A-WH-RAW', 'remark': 'P0合成包材收货凭证，仅本地体验', 'items': [{'purchaseOrderItemId': order['items'][0]['id'], 'materialTypeId': packaging['id'], 'receivedQuantity': 100, 'unit': '个', 'priceUnit': '个', 'unitPrice': 0.5}]})
        if 'pack_receipt_attachment' not in self.state['entities']:
            file = self.out / '01-purchase-receipt-synthetic.png'
            data = file.read_bytes()
            signed = self.call('POST', '/attachments/upload-url', {'fileName': 'p0-packaging-receipt.png', 'fileType': 'image/png', 'fileSize': len(data)})
            target = urlsplit(signed['uploadUrl'])
            assert target.scheme == 'http' and target.hostname == '127.0.0.1' and target.port == 18391, 'Private object fixture required'
            upload = requests.put(signed['uploadUrl'], data=data, headers={'Content-Type': 'image/png'}, timeout=30)
            upload.raise_for_status()
            self.create('pack_receipt_attachment', '/attachments', {'entityType': 'PURCHASE_RECEIPT', 'entityId': receipt['id'], 'fileName': 'p0-packaging-receipt.png', 'fileUrl': signed['fileUrl'], 'fileSize': len(data), 'fileType': 'image/png', 'fileHash': hashlib.sha256(data).hexdigest(), 'description': '本地合成收货凭证', 'uploadSource': 'API'})
        self.create('pack_receipt_confirmed', '/warehouse/receiving/receipts/' + receipt['id'] + '/confirm', {})
        transfer = self.create('production_transfer', '/transfers', {'transferType': 'WAREHOUSE_TO_WAREHOUSE', 'targetFactoryId': self.factory, 'sourceWarehouseId': 'REVIEW-TRACE-A-WH-RAW', 'targetWarehouseId': self.state['entities']['workshop']['id'], 'transferDate': date.today().isoformat(), 'remark': 'P0正常生产领料（本地合成）', 'items': [{'itemType': 'RAW_MATERIAL', 'materialTypeId': self.state['entities'][key]['id'], 'quantity': 40, 'unit': unit} for key, unit in [('raw', 'kg'), ('packaging', '个')]]})
        self.create('production_transfer_requested', '/transfers/' + transfer['id'] + '/request', {})
        self.create('production_transfer_confirmed', '/transfers/' + transfer['id'] + '/confirm', {})

    def normal_production(self):
        if 'labor_rate' not in self.state['entities']:
            self.state['entities']['labor_rate'] = self.call('PUT', '/config/cost-settings', {'laborHourlyRate': 20})
            self.persist()
        for key, quantity, defect in [('a', 8, 1), ('b', 20, 2)]:
            sku = self.state['entities']['sku_' + key]
            plan = self.create(self.run_key + '_plan_' + key, '/production-plans', {
                'clientRequestId': 'p0-' + self.run_key + '-web-plan-' + key, 'productTypeId': sku['id'],
                'plannedQuantity': quantity, 'plannedDate': date.today().isoformat(),
                'expectedCompletionDate': date.today().isoformat(), 'sourceType': 'MANUAL',
                'planType': 'FROM_INVENTORY', 'skipProcessReporting': False,
                'notes': 'P0正常Web报工、结单、仓库确认入库体验（合成测试）'})
            self.create(self.run_key + '_batch_' + key, '/production-plans/' + plan['id'] + '/create-batch', {})
            root = '/production-plans/' + plan['id']
            row = self.create(self.run_key + '_row_' + key, root + '/process-sheet/row/submit', {
                'clientRowId': 'p0-' + self.run_key + '-web-row-' + key, 'idempotencyKey': 'p0-' + self.run_key + '-web-row-' + key,
                'processCode': 'xiuyou', 'processOrder': 1, 'processName': 'P0体验熟制包装' + key.upper(),
                'processDate': date.today().isoformat(), 'productTypeId': sku['id'],
                'finished': True, 'inputQuantity': 10, 'inputUnit': 'kg',
                'outputQuantity': quantity, 'outputUnit': sku['unit'], 'unit': sku['unit'],
                'materialNodeId': 'finished', 'workflowPortId': 'out-fg',
                'materialInputTotals': [{'materialTypeId': self.state['entities']['raw']['id'], 'quantity': 10, 'unit': 'kg', 'workflowPortId': 'in-raw', 'materialNodeId': 'raw'}],
                'laborSegments': [{'startTime': '00:01', 'endTime': '00:11', 'workerCount': 1}]})
            print(json.dumps({'sku': key, 'row': row}, ensure_ascii=False))
            prefill = self.call('GET', root + '/settlement-prefill')
            (self.out / (self.run_key + '-prefill-' + key + '.json')).write_text(json.dumps(prefill, ensure_ascii=False, indent=2), encoding='utf-8')
            settlement = self.create(self.run_key + '_settlement_' + key, root + '/settle', {
                'idempotencyKey': 'p0-' + self.run_key + '-web-settle-' + key, 'confirm': True,
                'defectCountOutcome': 'COUNTED', 'defectQuantity': defect})
            receipt = self.create(self.run_key + '_finished_receipt_' + key, root + '/warehouse-receipt', {
                'idempotencyKey': 'p0-' + self.run_key + '-web-fg-receipt-' + key,
                'outputLines': [{'productTypeId': sku['id'], 'batchNumber': row['batchNumber'], 'receivedQuantity': quantity, 'quantityUnit': sku['unit']}]})
            print(json.dumps({'sku': key, 'settlement': settlement, 'receipt': receipt}, ensure_ascii=False))

    def normal_readback(self):
        for key in ['a', 'b']:
            plan, batch = (self.state['entities'][name + '_' + key] for name in [self.run_key + '_plan', self.run_key + '_batch'])
            evidence = {name: self.call('GET', path) for name, path in {
                'yield': '/production/batches/' + str(batch['id']) + '/yield',
                'cost': '/production/batches/' + batch['batchNumber'] + '/cost-breakdown',
                'trace': '/traceability/full/' + batch['batchNumber'],
                'settlement': '/production-plans/' + plan['id'] + '/settlement'}.items()}
            (self.out / (self.run_key + '-result-' + key + '.json')).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'sku': key, 'yield': evidence['yield'], 'cost': evidence['cost'], 'lineage': evidence['trace']['lineage']}, ensure_ascii=False))

    def sales(self):
        customer = 'REVIEW-TRACE-A-CUSTOMER'
        items = [{'productTypeId': self.state['entities']['sku_' + key]['id'], 'quantity': qty, 'unit': self.state['entities']['sku_' + key]['unit'], 'unitPrice': price, 'taxRate': 0, 'sourceWarehouseCode': 'WH-FG'} for key, qty, price in [('a', 4, 30), ('b', 10, 15)]]
        order = self.create(self.run_key + '_sales_order', '/sales/orders', {'customerId': customer, 'orderDate': date.today().isoformat(), 'requiredDeliveryDate': date.today().isoformat(), 'processingMode': 'STANDARD_SALE', 'materialSupplyMode': 'FACTORY_SUPPLIED', 'remark': 'P0多SKU出货闭环（合成测试）', 'items': items})
        confirmed = self.create(self.run_key + '_sales_confirmed', '/sales/orders/' + order['id'] + '/confirm', {})
        assert confirmed['status'] == 'FINANCE_APPROVED', 'Finish configured OA finance review first'
        order = self.call('GET', '/sales/orders/' + order['id'])
        master = self.create(self.run_key + '_delivery_master', '/sales/deliveries', {
            'idempotencyKey': 'p0-' + self.run_key + '-delivery-master', 'salesOrderId': order['id'], 'customerId': customer,
            'deliveryDate': date.today().isoformat(), 'deliveryAddress': 'P0本地合成体验收货地址（禁止实际发运）', 'remark': 'P0体验多SKU出货安排',
            'items': [{'salesOrderItemId': line['id'], 'productTypeId': line['productTypeId'], 'deliveredQuantity': line['quantity'], 'unit': line['unit'], 'unitPrice': line['unitPrice'], 'sourceWarehouseCode': 'WH-FG'} for line in order['items']]})
        master = self.call('GET', '/sales/deliveries/' + master['id'])
        old_shipment = self.state['entities'].get(self.run_key + '_shipment')
        if old_shipment and old_shipment.get('deliveryMethod') == 'PICKUP':
            self.create('invalid_method_shipment_cancelled', '/sales/deliveries/' + master['id'] + '/shipments/' + old_shipment['id'] + '/cancel', {})
        shipment = self.create(self.run_key + '_shipment_v2', '/sales/deliveries/' + master['id'] + '/shipments', {
            'idempotencyKey': 'p0-' + self.run_key + '-child-shipment-v2', 'plannedShipmentDate': date.today().isoformat(),
            'deliveryMode': 'FULL', 'deliveryMethod': 'SELF_PICKUP', 'remark': '仅本地合成出货',
            'items': [{'parentDeliveryItemId': line['id'], 'quantity': line['deliveredQuantity']} for line in master['items']]})
        shipment = self.call('GET', '/sales/deliveries/' + shipment['id'])
        for line in shipment['items']:
            key = next(key for key in ['a', 'b'] if self.state['entities']['sku_' + key]['id'] == line['productTypeId'])
            self.create(self.run_key + '_allocation_' + str(line['id']), '/sales-deliveries/items/' + str(line['id']) + '/batch-allocations', {
                'allocations': [{'finishedGoodsBatchId': self.state['entities'][self.run_key + '_finished_receipt_' + key]['finishedGoodsBatchId'], 'allocatedQty': line['deliveredQuantity']}]})
        shipped = self.create(self.run_key + '_shipped', '/warehouse/deliveries/' + shipment['id'] + '/confirm', {'actualQuantities': {str(line['id']): line['deliveredQuantity'] for line in shipment['items']}})
        print(json.dumps({'shipmentId': shipped['id'], 'status': shipped['status']}, ensure_ascii=False))

    def configure_metrics(self):
        definitions = [
            ('FACTORY_LU_YIELD_RATE', '实际出成率', '%', '合格成品净重÷实际首道投料净重；按已完工且已结单的生产单统计，包装净含量取生产计划固定快照。'),
            ('FACTORY_GOOD_OUTPUT_RATE', '生产良品率（按净重）', '%', '合格成品净重÷（合格净重＋明确清点的不良品净重）；未清点不按零不良计算。'),
            ('FACTORY_LU_UNIT_COST', '完整单位成本（每公斤合格品）', '元/kg', '复用现有核算；完整实际归集成本÷合格成品净重。缺价、缺工时或费用未录时不发布完整成本。'),
            ('FACTORY_ACTUAL_TOTAL_COST', '完整生产成本', '元', '复用同一实际核算来源，按完工期间累计；部分成本、参考成本不伪装为完整成本。'),
        ]
        existing = {item['code']: item for item in self.call('GET', '/canvas-indicators')}
        for index, (code, name, unit, description) in enumerate(definitions):
            data = {'code': code, 'name': name, 'category': 'FACTORY', 'unit': unit, 'description': description, 'computeStrategy': 'CACHED', 'cacheTtlSeconds': 300, 'displayOrder': index + 1}
            if code in existing:
                configured = self.call('PUT', '/canvas-indicators/' + existing[code]['id'], data)
            else:
                configured = self.call('POST', '/canvas-indicators', data)
            self.state['entities']['metric_' + code] = configured
            self.persist()
            print(json.dumps({'configuredMetric': code, 'id': configured['id']}, ensure_ascii=False))

    def verified_stock(self):
        workshop = self.create('verified_workshop', '/factory/warehouses', {'code': 'P0-VERIFIED-WKS', 'name': 'P0整链验收生产仓', 'type': 'WORKSHOP'})
        if 'verified_workshop_default' not in self.state['entities']:
            self.state['entities']['verified_workshop_default'] = self.call('PUT', '/factory/warehouse-defaults', {'purpose': 'WORKSHOP_DEFAULT', 'warehouseId': workshop['id']})
            self.persist()
        transfer = self.create('verified_transfer', '/transfers', {'transferType': 'WAREHOUSE_TO_WAREHOUSE', 'targetFactoryId': self.factory, 'sourceWarehouseId': 'REVIEW-TRACE-A-WH-RAW', 'targetWarehouseId': workshop['id'], 'transferDate': date.today().isoformat(), 'remark': 'P0修复后整链验收正常领料', 'items': [{'itemType': 'RAW_MATERIAL', 'materialTypeId': self.state['entities'][key]['id'], 'quantity': quantity, 'unit': unit} for key, quantity, unit in [('raw', 20, 'kg'), ('packaging', 30, '个')]]})
        self.create('verified_transfer_requested', '/transfers/' + transfer['id'] + '/request', {})
        self.create('verified_transfer_confirmed', '/transfers/' + transfer['id'] + '/confirm', {})

    def finish_costs(self):
        for key in ['a', 'b']:
            plan = self.state['entities'][self.run_key + '_plan_' + key]
            name = self.run_key + '_costs_' + key
            if name not in self.state['entities']:
                self.state['entities'][name] = self.call('PUT', '/production-plans/' + plan['id'] + '/costs?equipmentCost=12&otherCost=3')
                self.persist()
            result = self.create(self.run_key + '_recalculated_' + key, '/production-plans/' + plan['id'] + '/recalculate-settled-costs', {})
            print(json.dumps({'sku': key, 'costRecalculation': result}, ensure_ascii=False))

    def repair_initial_quality(self):
        for key, count in [('a', 1), ('b', 2)]:
            plan = self.state['entities']['normal_plan_' + key]
            self.create('normal_quality_repaired_' + key, '/stocktakes/pending-defect-counts/' + plan['id'] + '/resolve', {'outcome': 'COUNTED', 'defectQuantity': count})

    def repair_receipt_previews(self):
        """Replace only this run's invalid synthetic preview metadata through the normal attachment API."""
        receipts = self.call('GET', '/warehouse/receiving/receipts/by-order/' + self.state['entities']['purchase_order']['id'])
        targets = [('raw', receipts[0]['id']), ('pack', self.state['entities']['pack_receipt']['id'])]
        data = (self.out / '01-purchase-receipt-synthetic.png').read_bytes()
        for key, receipt_id in targets:
            attachments = self.call('GET', '/attachments?entityType=PURCHASE_RECEIPT&entityId=' + receipt_id)
            invalid = [item for item in attachments if urlsplit(item['fileUrl']).hostname == '127.0.0.1' and urlsplit(item['fileUrl']).port is None]
            name = 'repaired_receipt_preview_' + key
            if name not in self.state['entities']:
                signed = self.call('POST', '/attachments/upload-url', {'fileName': 'p0-' + key + '-receipt-preview.png', 'fileType': 'image/png', 'fileSize': len(data)})
                target = urlsplit(signed['uploadUrl'])
                assert target.hostname == '127.0.0.1' and target.port == 18391, 'Owned local object store required'
                upload = requests.put(signed['uploadUrl'], data=data, headers={'Content-Type': 'image/png'}, timeout=30)
                upload.raise_for_status()
                self.create(name, '/attachments', {'entityType': 'PURCHASE_RECEIPT', 'entityId': receipt_id,
                    'fileName': 'p0-' + key + '-receipt-preview.png', 'fileUrl': signed['fileUrl'], 'fileSize': len(data),
                    'fileType': 'image/png', 'description': 'P0合成收货凭证，修复本地端口预览', 'uploadSource': 'API'})
            fixed = self.state['entities'][name]
            target = urlsplit(fixed['fileUrl'])
            assert target.hostname == '127.0.0.1' and target.port == 18391
            download = requests.get(fixed['fileUrl'], timeout=30)
            download.raise_for_status()
            assert hashlib.sha256(download.content).digest() == hashlib.sha256(data).digest()
            for old in invalid:
                self.call('DELETE', '/attachments/' + old['id'])
            print(json.dumps({'receipt': receipt_id, 'validPreview': fixed['id'], 'bytesVerified': len(data), 'invalidMetadataSoftDeleted': len(invalid)}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--allow-test-writes', choices=['NON_PRODUCTION_ONLY'], required=True)
    parser.add_argument('--run-key', choices=['normal', 'verified'], default='normal')
    parser.add_argument('step', choices=['identity', 'master', 'purchase', 'receipt', 'workflows', 'stock', 'normal', 'readback', 'sales', 'metrics', 'verified-stock', 'costs', 'quality-repair', 'receipt-previews'])
    args = parser.parse_args()
    flow = BusinessFlow(args.url, args.credentials, args.out, args.run_key)
    if args.step == 'master':
        flow.master_data()
    elif args.step == 'purchase':
        flow.purchase()
    elif args.step == 'receipt':
        flow.receipt_readback()
    elif args.step == 'workflows':
        flow.workflows()
    elif args.step == 'stock':
        flow.prepare_stock()
    elif args.step == 'normal':
        flow.normal_production()
    elif args.step == 'readback':
        flow.normal_readback()
    elif args.step == 'sales':
        flow.sales()
    elif args.step == 'metrics':
        flow.configure_metrics()
    elif args.step == 'verified-stock':
        flow.verified_stock()
    elif args.step == 'costs':
        flow.finish_costs()
    elif args.step == 'quality-repair':
        flow.repair_initial_quality()
    elif args.step == 'receipt-previews':
        flow.repair_receipt_previews()
    else:
        print(json.dumps(flow.state['identity'], ensure_ascii=False))


if __name__ == '__main__':
    main()
