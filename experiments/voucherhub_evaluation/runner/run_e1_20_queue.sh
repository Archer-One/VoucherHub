#!/usr/bin/env bash
set -euo pipefail

runner=/home/ubuntu/voucherhub/run_e1_series.sh
output=/home/ubuntu/voucherhub/results/evaluation/e1

REPETITIONS=20 "$runner" same_city_1to1 \
  76hxjcm2qfso55njk3fxqulads5tuwyxzjatw5p6ezuym6bsinyeuzqd.onion \
  tokyo3 "$output"
REPETITIONS=20 "$runner" same_city_1to3 \
  76hxjcm2qfso55njk3fxqulads5tuwyxzjatw5p6ezuym6bsinyeuzqd.onion,g74dgn5yhvcdoxmef3ysfx5n54ghspa7udcj7lognbmytz2bwmvjopad.onion,wyhl5o7vkwibqtx2g57zjgqyjslvky5v3icnrttivuynirzgc3kjcnid.onion \
  tokyo3,tokyo6,tokyo7 "$output"
REPETITIONS=20 "$runner" regional_1to1 \
  p6iqu3ncdtkakqkxlcaylasbpskrnfv7mvq6ektbbczwhsf765zptfyd.onion \
  singapore1 "$output"
REPETITIONS=20 "$runner" regional_1to3 \
  5yqksfmfewhx4ygfpxef65zx7anezji2udiz2wn5cutwog4f22meaoqd.onion,7o5vk5ttisyzjtgzlpri5jnjjtezothnj4ms5l2lwtusxa5522tuviqd.onion,p6iqu3ncdtkakqkxlcaylasbpskrnfv7mvq6ektbbczwhsf765zptfyd.onion \
  seoul1,seoul2,singapore1 "$output"
REPETITIONS=20 "$runner" global_1to1 \
  hboi5zdwozb2dz4iov2s53hhcvp34z57e6jzk6upzfceteqvv3cqg5ad.onion \
  virginia1 "$output"
REPETITIONS=20 "$runner" global_1to3 \
  ucbrfdgt6e3ldfm7djb3goqva6uxq34qs3tkh5fcefmwcftqi7pl4xyd.onion,vv5nekwisck4i77lve27f7oq26j4jlnr72yjhzcnzml2zsn5qabkz5ad.onion,hboi5zdwozb2dz4iov2s53hhcvp34z57e6jzk6upzfceteqvv3cqg5ad.onion \
  frankfurt1,frankfurt2,virginia1 "$output"
