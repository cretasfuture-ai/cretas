import unittest
from capacity import check_available, MIN_FREE

class CapacityTests(unittest.TestCase):
    def test_exact_admission_boundary(self):
        check_available(MIN_FREE)
    def test_one_byte_short_is_rejected(self):
        with self.assertRaises(RuntimeError): check_available(MIN_FREE-1)
    def test_reserve_is_not_treated_as_build_headroom(self):
        with self.assertRaises(RuntimeError): check_available(100*2**30)
    def test_below_reserve_is_rejected(self):
        with self.assertRaises(RuntimeError): check_available(99*2**30)
    def test_invalid_measurements_fail_closed(self):
        for value in (None, True, '125', -1):
            with self.subTest(value=value), self.assertRaises(RuntimeError): check_available(value)

if __name__ == '__main__': unittest.main()
