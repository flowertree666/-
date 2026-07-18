$base='D:\18\travel-dataset\nanjing'
$poiFile=Join-Path $base 'pois.jsonl'
$evidenceFile=Join-Path $base 'official_evidence.jsonl'
$chunkFile=Join-Path $base 'knowledge_chunks.jsonl'
$sourceFile=Join-Path $base 'sources.jsonl'
$now='2026-07-16T15:30:00+08:00'
$pois=Get-Content $poiFile -Encoding utf8 | Where-Object {$_.Trim()} | ForEach-Object {$_|ConvertFrom-Json}
$evidence=Get-Content $evidenceFile -Encoding utf8 | Where-Object {$_.Trim()} | ForEach-Object {$_|ConvertFrom-Json}
function Write-JsonLine($path,$value){($value|ConvertTo-Json -Compress -Depth 8)|Add-Content -LiteralPath $path -Encoding utf8}
$covered=@{}
foreach($e in $evidence){
  foreach($poiId in $e.poi_ids){
    $p=$pois|Where-Object {$_.poi_id -eq $poiId}|Select-Object -First 1
    if(-not $p){continue}
    $covered[$poiId]=$true
    $sid=$e.evidence_id+'-'+$poiId
    $source=[ordered]@{source_id=$sid;poi_id=$poiId;source_name=$e.source_name;source_type='official';title=$e.title;source_url=$e.source_url;published_at=$e.published_at;collected_at=$now;valid_until=$e.valid_until;facts=$e.facts;confidence='high'}
    Write-JsonLine $sourceFile $source
    $chunk=[ordered]@{chunk_id=$poiId+'-OFF-'+$e.evidence_id.Substring(7);city='Nanjing';poi_id=$poiId;title='Official travel evidence: '+$p.name;content=($e.facts -join ' ');tags=@('official','reservation','opening','recheck');fact_type='official_fact';source_ids=@($sid);source_url=$e.source_url;published_at=$e.published_at;collected_at=$now;valid_until=$e.valid_until}
    Write-JsonLine $chunkFile $chunk
  }
}
foreach($p in $pois){
  if($covered.ContainsKey($p.poi_id)){continue}
  $sid='NJ-OFF-RECHECK-'+$p.poi_id
  $source=[ordered]@{source_id=$sid;poi_id=$p.poi_id;source_name='Nanjing Municipal Government';source_type='official';title='Official information recheck requirement';source_url='https://www.nanjing.gov.cn/njxx/202407/t20240704_4706024.html';published_at='2024-07-04T00:00:00+08:00';collected_at=$now;valid_until='2026-08-15';facts=@('No current POI-specific official notice was collected in this batch. Verify opening hours, reservations, ticket rules and temporary notices with the venue or authority before travel.');confidence='medium'}
  Write-JsonLine $sourceFile $source
  $chunk=[ordered]@{chunk_id=$p.poi_id+'-OFF-RECHECK';city='Nanjing';poi_id=$p.poi_id;title='Official information recheck: '+$p.name;content='No current POI-specific official notice was collected in this batch. Do not treat map ratings or old guides as live facts. Before itinerary generation, retrieve the venue or authority official page to verify opening hours, reservations, ticket rules, weather restrictions and temporary closure notices.';tags=@('official_recheck','dynamic_data','travel_validation');fact_type='official_recheck_requirement';source_ids=@($sid);source_url=$source.source_url;published_at=$source.published_at;collected_at=$now;valid_until=$source.valid_until}
  Write-JsonLine $chunkFile $chunk
}
