$base = 'D:\18\travel-dataset\nanjing'
$targets = @(
  @{id='NJ-002'; name='明孝陵'; aliases=@('明孝陵景区'); category=@('历史人文','世界遗产','风景名胜'); duration=210; intensity='中等'; tags=@('历史人文','摄影','亲子'); cluster='钟山-紫金山片区'},
  @{id='NJ-003'; name='美龄宫'; aliases=@('美龄宫景区'); category=@('历史人文','建筑'); duration=90; intensity='低'; tags=@('历史人文','摄影'); cluster='钟山-紫金山片区'},
  @{id='NJ-004'; name='音乐台'; aliases=@('中山陵音乐台'); category=@('历史人文','建筑','风景名胜'); duration=60; intensity='低'; tags=@('摄影','亲子','历史人文'); cluster='钟山-紫金山片区'},
  @{id='NJ-005'; name='灵谷寺'; aliases=@('灵谷景区','灵谷寺景区'); category=@('历史人文','寺庙','风景名胜'); duration=150; intensity='中等'; tags=@('历史人文','自然风光'); cluster='钟山-紫金山片区'},
  @{id='NJ-006'; name='南京博物院'; aliases=@('南京博物院景区'); category=@('博物馆','历史人文'); duration=240; intensity='低'; tags=@('历史人文','亲子','雨天'); cluster='中山东路片区'},
  @{id='NJ-007'; name='紫金山天文台'; aliases=@('南京市紫金山天文台博物馆'); category=@('博物馆','自然科学','历史人文'); duration=120; intensity='中等'; tags=@('亲子','摄影','自然风光'); cluster='钟山-紫金山片区'},
  @{id='NJ-008'; name='梧桐大道'; aliases=@('陵园路梧桐大道'); category=@('城市漫游','摄影'); duration=45; intensity='低'; tags=@('摄影','城市漫游'); cluster='钟山-紫金山片区'},
  @{id='NJ-009'; name='玄武湖公园'; aliases=@('玄武湖景区'); category=@('自然风光','城市公园'); duration=180; intensity='低'; tags=@('自然风光','亲子','摄影'); cluster='玄武湖-城墙片区'},
  @{id='NJ-010'; name='鸡鸣寺'; aliases=@('古鸡鸣寺'); category=@('历史人文','寺庙'); duration=90; intensity='低'; tags=@('历史人文','摄影'); cluster='玄武湖-城墙片区'},
  @{id='NJ-011'; name='南京城墙台城段'; aliases=@('台城','南京城墙台城景区'); category=@('历史人文','城市漫游'); duration=90; intensity='低'; tags=@('历史人文','摄影','城市漫游'); cluster='玄武湖-城墙片区'},
  @{id='NJ-012'; name='总统府'; aliases=@('南京总统府'); category=@('历史人文','博物馆'); duration=180; intensity='低'; tags=@('历史人文','亲子'); cluster='长江路片区'},
  @{id='NJ-013'; name='六朝博物馆'; aliases=@('南京六朝博物馆'); category=@('博物馆','历史人文'); duration=120; intensity='低'; tags=@('历史人文','雨天'); cluster='长江路片区'},
  @{id='NJ-014'; name='颐和路历史文化街区'; aliases=@('颐和路公馆区'); category=@('城市漫游','历史人文'); duration=120; intensity='低'; tags=@('摄影','城市漫游','历史人文'); cluster='鼓楼片区'},
  @{id='NJ-015'; name='先锋书店（五台山店）'; aliases=@('先锋书店五台山总店','先锋书店'); category=@('城市漫游','文化空间'); duration=60; intensity='低'; tags=@('城市漫游','摄影'); cluster='五台山片区'},
  @{id='NJ-016'; name='侵华日军南京大屠杀遇难同胞纪念馆'; aliases=@('南京大屠杀遇难同胞纪念馆'); category=@('纪念馆','历史人文'); duration=180; intensity='低'; tags=@('历史人文','亲子'); cluster='河西片区'},
  @{id='NJ-017'; name='雨花台风景区'; aliases=@('雨花台烈士陵园'); category=@('历史人文','城市公园'); duration=180; intensity='中等'; tags=@('历史人文','亲子','自然风光'); cluster='雨花台片区'},
  @{id='NJ-018'; name='牛首山文化旅游区'; aliases=@('南京牛首山文化旅游区','牛首山'); category=@('历史人文','自然风光','风景名胜'); duration=300; intensity='中等'; tags=@('历史人文','亲子','摄影'); cluster='江宁牛首山片区'},
  @{id='NJ-019'; name='大报恩寺遗址景区'; aliases=@('大报恩寺遗址公园'); category=@('历史人文','博物馆'); duration=180; intensity='低'; tags=@('历史人文','亲子','夜游'); cluster='秦淮南部片区'},
  @{id='NJ-020'; name='中华门城堡'; aliases=@('中华门瓮城'); category=@('历史人文','城墙'); duration=120; intensity='中等'; tags=@('历史人文','摄影'); cluster='秦淮南部片区'},
  @{id='NJ-021'; name='老门东历史文化街区'; aliases=@('老门东'); category=@('城市漫游','历史人文','美食街区'); duration=150; intensity='低'; tags=@('城市漫游','美食','摄影'); cluster='秦淮南部片区'},
  @{id='NJ-022'; name='夫子庙秦淮风光带'; aliases=@('夫子庙','南京夫子庙'); category=@('历史人文','城市漫游','夜游'); duration=180; intensity='低'; tags=@('历史人文','美食','夜游'); cluster='夫子庙-秦淮片区'},
  @{id='NJ-023'; name='中国科举博物馆'; aliases=@('江南贡院','南京中国科举博物馆'); category=@('博物馆','历史人文'); duration=150; intensity='低'; tags=@('历史人文','亲子','雨天'); cluster='夫子庙-秦淮片区'},
  @{id='NJ-024'; name='瞻园'; aliases=@('南京瞻园'); category=@('历史人文','园林'); duration=90; intensity='低'; tags=@('历史人文','摄影'); cluster='夫子庙-秦淮片区'},
  @{id='NJ-025'; name='秦淮河画舫夜游'; aliases=@('夫子庙秦淮河画舫','秦淮河游船'); category=@('夜游','体验项目'); duration=60; intensity='低'; tags=@('夜游','摄影','亲子'); cluster='夫子庙-秦淮片区'; experience=$true},
  @{id='NJ-026'; name='甘熙故居'; aliases=@('南京市民俗博物馆','甘熙宅第'); category=@('历史人文','博物馆'); duration=90; intensity='低'; tags=@('历史人文','摄影'); cluster='南捕厅片区'},
  @{id='NJ-027'; name='朝天宫'; aliases=@('南京市博物馆朝天宫'); category=@('历史人文','博物馆'); duration=120; intensity='低'; tags=@('历史人文','摄影','雨天'); cluster='朝天宫片区'},
  @{id='NJ-028'; name='阅江楼'; aliases=@('阅江楼景区'); category=@('历史人文','自然风光','风景名胜'); duration=120; intensity='中等'; tags=@('历史人文','摄影'); cluster='狮子山片区'},
  @{id='NJ-029'; name='南京长江大桥'; aliases=@('南京长江大桥南堡公园'); category=@('城市地标','历史人文'); duration=90; intensity='低'; tags=@('城市漫游','摄影','历史人文'); cluster='长江大桥片区'},
  @{id='NJ-030'; name='幕燕滨江风貌区'; aliases=@('幕燕滨江风景区','燕子矶公园'); category=@('自然风光','城市漫游'); duration=180; intensity='中等'; tags=@('自然风光','摄影','城市漫游'); cluster='幕府山滨江片区'}
)
function Norm([string]$s){ return ($s -replace '\s','' -replace '[·•()（）—-]','' -replace '(旅游景区|风景名胜区|景区)$','') }
function IsBad([string]$name){ return $name -match '停车|入口|出口|售票|游客中心|服务区|公交站|地铁站|厕所' }
function IsExpected([string]$actual,[hashtable]$target){
  if([string]::IsNullOrWhiteSpace($actual) -or (IsBad $actual)){return $false}
  $value=Norm $actual
  foreach($candidate in @($target.name)+@($target.aliases)){
    $expected=Norm $candidate
    if($value -eq $expected -or $value.Contains($expected) -or $expected.Contains($value)){return $true}
  }
  return $false
}
$poifile=Join-Path $base 'pois.jsonl'; $sourcefile=Join-Path $base 'sources.jsonl'; $chunkfile=Join-Path $base 'knowledge_chunks.jsonl'
$existing = @{}; Get-Content $poifile -Encoding utf8 | Where-Object {$_.Trim()} | ForEach-Object { $item=$_ | ConvertFrom-Json; $existing[$item.poi_id]=$true }
$summary=@(); $now='2026-07-16T14:50:00+08:00'
foreach($t in $targets){
  if($existing.ContainsKey($t.id)){continue}
  $assetDir=Join-Path $base ('poi_assets\'+$t.id); New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
  $match=$null; $usedKeyword=''
  foreach($keyword in @($t.name)+@($t.aliases)){
    $exactPath=Join-Path $assetDir 'amap_poi_match.json'
    & curl.exe -sG 'http://localhost:8080/api/amap/poi-match' --data-urlencode 'city=南京市' --data-urlencode ('keyword='+$keyword) -o $exactPath
    try{$candidateMatch=Get-Content -LiteralPath $exactPath -Encoding utf8 -Raw | ConvertFrom-Json}catch{continue}
    if(IsExpected $candidateMatch.name $t){$match=$candidateMatch;$usedKeyword=$keyword;break}
    Start-Sleep -Milliseconds 900
  }
  if(-not $match){$summary += [pscustomobject]@{id=$t.id;name=$t.name;status='no_verified_match';details='高德未返回符合主POI规则的候选'};continue}
  & curl.exe -sG 'http://localhost:8080/api/amap/poi' --data-urlencode 'city=南京市' --data-urlencode ('keyword='+$usedKeyword) -o (Join-Path $assetDir 'amap_poi_raw.json')
  $candidate=[pscustomobject]@{id=($match.sourceUrl -replace '^.*/','');name=$match.name;address=$match.address;location=($match.longitude.ToString([System.Globalization.CultureInfo]::InvariantCulture)+','+$match.latitude.ToString([System.Globalization.CultureInfo]::InvariantCulture));adname=$match.district;type=$match.tags;business=[pscustomobject]@{rating=if($match.heatScore -gt 50){(($match.heatScore-50)/10).ToString([System.Globalization.CultureInfo]::InvariantCulture)}else{''};opentime_today=$match.openingHours}}
  $coords=$candidate.location -split ','; if($coords.Count -ne 2){$summary += [pscustomobject]@{id=$t.id;name=$t.name;status='invalid_coordinate';details=$candidate.id};continue}
  $rating=if($candidate.business.rating){[double]$candidate.business.rating}else{$null}; $amapUrl='https://www.amap.com/place/'+$candidate.id
  $poi=[ordered]@{poi_id=$t.id;city='南京市';name=$t.name;aliases=@($t.aliases);category=@($t.category);district=$candidate.adname;address=$candidate.address;longitude=[double]$coords[0];latitude=[double]$coords[1];coordinate_system='GCJ-02';amap_poi_id=$candidate.id;amap_rating=$rating;amap_type=$candidate.type;opening_hours=if($candidate.business.opentime_today){$candidate.business.opentime_today}else{''};reservation_required=$null;ticket_info='';recommended_duration_minutes=$t.duration;physical_intensity=$t.intensity;stairs_or_hiking=($t.intensity -eq '中等');best_seasons=@('春季','秋季');suitable_tags=@($t.tags);nearby_cluster=$t.cluster;data_sources=@('NJ-S-'+$t.id.Substring(3));collected_at=$now;updated_at=$now}
  ($poi | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $poifile -Encoding utf8
  $source=[ordered]@{source_id='NJ-S-'+$t.id.Substring(3);poi_id=$t.id;source_name='高德地图开放平台';source_type='map';title=('高德地点搜索：'+$candidate.name+'（南京市）');source_url=$amapUrl;published_at=$null;collected_at=$now;valid_until='2026-10-16';facts=@('高德POI ID：'+$candidate.id,'名称：'+$candidate.name,'地址：'+$candidate.address,'GCJ-02坐标：'+$candidate.location,'区县：'+$candidate.adname)+$(if($rating){@('高德评分：'+$rating)}else{@()});confidence='high';raw_asset=('poi_assets/'+$t.id+'/amap_poi_raw.json')}
  ($source | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $sourcefile -Encoding utf8
  $isExperience = $t.ContainsKey('experience') -and $t.experience
  $kind=if($isExperience){'体验型POI'}else{'景点'}
  $c1=[ordered]@{chunk_id=($t.id+'-C01');city='南京市';poi_id=$t.id;title=($t.name+'地图定位与基础属性');content=($t.name+'为南京市'+$candidate.adname+'的'+$kind+'。高德地图检索到的主POI名称为“'+$candidate.name+'”，地址为“'+$candidate.address+'”，GCJ-02坐标为'+$candidate.location+'。高德地点搜索显示的类别为“'+$candidate.type+'”'+$(if($rating){'，评分为'+$rating+'。'}else{'，未返回公开评分。'})+'该信息用于路线定位、同片区聚类和高德路线规划；开放、预约和票价应另以官方公告核验。');tags=@('地图定位','POI','路线规划')+@($t.tags);fact_type='map_fact';source_ids=@('NJ-S-'+$t.id.Substring(3));source_url=$amapUrl;published_at=$null;collected_at=$now;valid_until='2026-10-16'}
  $activity=if($t.intensity -eq '中等'){'包含较多步行、台阶或较大游览范围'}else{'以常规步行参观为主'}
  $c2=[ordered]@{chunk_id=($t.id+'-C02');city='南京市';poi_id=$t.id;title=($t.name+'路线安排与体力约束');content=($t.name+'建议预留约'+$t.duration+'分钟，体力等级为'+$t.intensity+'。该建议基于POI类别、同片区聚类和路线规划需求形成：'+$activity+'。优先与“'+$t.cluster+'”内的POI组合，可减少跨区交通；若用户当天已安排登山、长距离徒步或多个大型场馆，应降低其他项目密度并预留餐饮、休息和交通缓冲。该片段是可解释的规划规则，不是景区官方开放、票价或预约承诺。');tags=@('行程规划','体力安排','片区聚类')+@($t.tags);fact_type='derived_rule';source_ids=@('NJ-S-'+$t.id.Substring(3));source_url=$amapUrl;published_at=$null;collected_at=$now;valid_until='2026-10-16'}
  ($c1 | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $chunkfile -Encoding utf8
  ($c2 | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $chunkfile -Encoding utf8
  @"
# $($t.id) $($t.name) 原始素材

- 采集时间：$now
- 高德主 POI：$($candidate.id)（$($candidate.name)）
- 原始响应：`amap_poi_raw.json`
- 说明：同次文本搜索可能包含其他候选；只有上述主 POI 的字段被写入 JSONL。
- 当前记录只有地图事实与标记为 `derived_rule` 的路线规划规则。开放时间、预约和门票需要补充官方来源后再写入。
"@ | Set-Content -LiteralPath (Join-Path $assetDir 'README.md') -Encoding utf8
  $summary += [pscustomobject]@{id=$t.id;name=$t.name;status='written';details=($candidate.name+' / '+$candidate.id)}
  Start-Sleep -Milliseconds 200
}
$summary | ConvertTo-Json -Depth 4
