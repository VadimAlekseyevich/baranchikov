# Comparison of measured throughput

| Scaled layer | 1 replica, rps | 2 replicas, rps | 3 replicas, rps | Speedup ×2 | Speedup ×3 |
|---|---:|---:|---:|---:|---:|
| facade | 205.78 | 206.16 | 265.25 | 1.00x | 1.29x |
| business | 268.70 | 378.75 | 281.76 | 1.41x | 1.05x |
| storage | 300.01 | 334.05 | 344.11 | 1.11x | 1.15x |

Workload: independent UDP GET_OPEN_ORDERS requests. Comparison is an observed single run, not a promised linear speedup.