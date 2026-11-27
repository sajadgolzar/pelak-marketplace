import test from 'node:test';import assert from 'node:assert/strict';import {calculateImport} from '../pelak-import-cost-core.mjs';import {listingLink} from '../pelak-market-view.mjs';
const a={price:'۲۰٬۰۰۰',rate:'۱۰۰٬۰۰۰',shipping:'100000000',customs:'200000000',registration:'50000000',other:'0'};
test('calculate all entered costs in toman',()=>assert.equal(calculateImport(a).total,2350000000));
test('reject unknown or invalid costs',()=>{for(const price of ['', '-1','1e5','1.234','0'])assert.throws(()=>calculateImport({...a,price}));assert.throws(()=>calculateImport({...a,customs:''}));assert.throws(()=>calculateImport({...a,rate:0}));});
test('accept decimal foreign price and reject overflow',()=>{assert.equal(calculateImport({...a,price:'1٫25',rate:100}).purchase,125);assert.throws(()=>calculateImport({...a,price:'9000000000000000',rate:100}));});
test('exact model query encoded safely',()=>assert.equal(listingLink({model:'پژو 207 &'}),'/?q='+encodeURIComponent('پژو 207 &')));
