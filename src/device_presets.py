from export_wallpapers import PRESETS as ORIGINAL_PRESETS

PRESETS=list(ORIGINAL_PRESETS)

TABLETS=[
 ('pixel','pixel_tablet',1600,2560),
 ('ipad','ipad_mini_6_a17_pro',1488,2266),
 ('ipad','ipad_9',1620,2160),
 ('ipad','ipad_10_a16_air_11',1640,2360),
 ('ipad','ipad_pro_11_2018_2022',1668,2388),
 ('ipad','ipad_pro_11_m4_m5',1668,2420),
 ('ipad','ipad_air_13_pro_12_9',2048,2732),
 ('ipad','ipad_pro_13_m4_m5',2064,2752),
]
for brand,name,w,h in TABLETS:
 PRESETS += [(f'tablet/{brand}',name+'_portrait',w,h),
             (f'tablet/{brand}',name+'_landscape',h,w)]

FOLDABLES=[
 ('samsung','galaxy_z_fold7',('cover',1080,2520),('inner',1968,2184)),
 ('google','pixel_10_pro_fold',('cover',1080,2364),('inner',2076,2152)),
 ('oneplus','oneplus_open',('cover',1116,2484),('inner',2268,2440)),
 ('oppo','oppo_find_n5',('cover',1140,2616),('inner',2248,2480)),
 ('honor','honor_magic_v5',('cover',1060,2376),('inner',2172,2352)),
 ('samsung','galaxy_z_flip7',('cover',948,1048),('inner',1080,2520)),
 ('motorola','motorola_razr_ultra_2025',('cover',1080,1272),('inner',1224,2992)),
]
for brand,name,*screens in FOLDABLES:
 for screen,w,h in screens:
  PRESETS += [(f'foldable/{brand}',f'{name}_{screen}_portrait',w,h),
              (f'foldable/{brand}',f'{name}_{screen}_landscape',h,w)]

assert len(PRESETS)==60
