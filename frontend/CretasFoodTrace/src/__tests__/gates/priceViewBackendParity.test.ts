import fs from 'fs';
import path from 'path';
import { PRICE_VIEW_ROLES } from '../../utils/priceViewAccess';
import { PRICE_VIEW_ROLES as rowRoles } from '../../config/rowActionsConfig';
import { PRICE_VIEW_ROLES as storeRoles } from '../../store/canViewPriceStore';

it('mobile price checks share the exact backend special-scope role set', () => {
  const source = fs.readFileSync(path.resolve(__dirname,
    '../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/PermissionServiceImpl.java'), 'utf8');
  const declaration = source.match(/PRICE_VIEW_ROLES\s*=\s*Set\.of\(([\s\S]*?)\);/);
  expect(declaration).not.toBeNull();
  const roles = [...declaration![1]!.matchAll(/FactoryUserRole\.([a-z_]+)/g)].map(match => match[1]!);
  expect(roles.length).toBeGreaterThan(0);
  expect([...PRICE_VIEW_ROLES].sort()).toEqual(roles.sort());
  expect(rowRoles).toBe(PRICE_VIEW_ROLES);
  expect(storeRoles).toBe(PRICE_VIEW_ROLES);
});
