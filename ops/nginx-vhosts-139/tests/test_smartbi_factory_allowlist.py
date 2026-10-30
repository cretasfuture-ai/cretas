"""The new tenant joins existing SmartBI cutover routes without widening their scope."""
import re
import unittest
from pathlib import Path


class SmartBiFactoryAllowlistTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (Path(__file__).parents[1] / 'smart-bi-routing.conf').read_text(encoding='utf-8')
        cls.routes = [re.compile(line.split('location ~ ', 1)[1].rsplit(' {', 1)[0])
                      for line in source.splitlines() if line.startswith('location ~ ')]

    def routed(self, tenant, suffix):
        path = f'/api/mobile/{tenant}/smart-bi/{suffix}'
        return any(route.fullmatch(path) for route in self.routes)

    def test_new_factory_uses_existing_analysis_and_data_routes(self):
        for suffix in ('analysis/finance', 'analysis/sales', 'alerts', 'recommendations',
                       'data-date-range', 'query-templates', 'datasource', 'incentive-plan'):
            with self.subTest(suffix=suffix):
                self.assertTrue(self.routed('FOOD_0000_001', suffix))

    def test_neighbor_factory_is_not_implicitly_enrolled(self):
        self.assertFalse(self.routed('FOOD_0000_002', 'analysis/finance'))
        self.assertFalse(self.routed('UNENROLLED_FACTORY', 'query-templates'))

    def test_existing_factory_and_restaurant_routes_remain(self):
        self.assertTrue(self.routed('F006', 'analysis/finance'))
        self.assertTrue(self.routed('LIUSHANMEN', 'query-templates'))
        self.assertTrue(self.routed('RES_3101_009', 'analysis/kitchen-cost'))

    def test_java_authoritative_paths_stay_out_of_python_cutover(self):
        for suffix in ('dashboard', 'drill-down', 'analysis/dynamic', 'analysis/production',
                       'analysis/quality'):
            with self.subTest(suffix=suffix):
                self.assertFalse(self.routed('FOOD_0000_001', suffix))


if __name__ == '__main__':
    unittest.main()
