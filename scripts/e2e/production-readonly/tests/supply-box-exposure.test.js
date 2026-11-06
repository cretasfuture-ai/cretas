'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {assertBoxExposure}=require('../scenarios/supply-box-exposure-readonly');
function fixture(){return {scope:'DISCOVERED_LINEAGE',inventoryBasis:'PHYSICAL_ON_HAND_INCLUDING_RESERVED',
  quantityMeaning:'PACKAGING_SNAPSHOT_EQUIVALENT',stock:{complete:true,totalBoxQuantity:'9007199254740993.125',knownBoxSubtotal:'9007199254740993.125',knownSourceCount:1,unknownSourceCount:0},
  shipped:{complete:false,totalBoxQuantity:null,knownBoxSubtotal:null,knownSourceCount:0,unknownSourceCount:1},
  stockSources:[{sourceId:'fg1',finishedGoodsBatchId:'fg1',boxQuantity:'9007199254740993.125',gaps:[]}],
  shipmentSources:[],customers:[],gaps:[{code:'SHIPMENT_SCOPE_EMPTY',message:'未知'}]};}
test('preserves exact large box-equivalent strings and unknown shipment totals',()=>assert.equal(assertBoxExposure(fixture()),true));
test('rejects invented totals, float conversion and missing source uncertainty',()=>{
  for(const change of [x=>{x.shipped.totalBoxQuantity='0'},x=>{x.stock.totalBoxQuantity=1},
    x=>{x.stock.unknownSourceCount=1},x=>{x.stock.totalBoxQuantity='1'},
    x=>{x.stockSources[0].boxQuantity=null},x=>{x.stockSources=[]},
    x=>{x.stockSources.push({...x.stockSources[0]})},x=>{x.quantityMeaning='SEALED_CARTONS'}]){
    const value=fixture();change(value);assert.throws(()=>assertBoxExposure(value));
  }
});
