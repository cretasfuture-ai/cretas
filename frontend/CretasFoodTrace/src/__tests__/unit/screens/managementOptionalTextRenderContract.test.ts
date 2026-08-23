import fs from 'fs';
import path from 'path';

describe('management optional text render contract', () => {
  const readScreen = (name: string): string => fs.readFileSync(
    path.resolve(__dirname, '../../../screens/management', name),
    'utf8',
  );

  it.each([
    ['SupplierManagementScreen.tsx', ['contactPerson', 'phone', 'businessType', 'address']],
    ['CustomerManagementScreen.tsx', ['customerType', 'contactPerson', 'phone', 'industry', 'shippingAddress']],
  ])('coerces empty optional strings before rendering inside a View in %s', (fileName, fields) => {
    const source = readScreen(fileName);

    for (const field of fields) {
      expect(source).toContain(`Boolean(${fileName.startsWith('Supplier') ? 'supplier' : 'customer'}.${field}) && (`);
    }
  });
});
