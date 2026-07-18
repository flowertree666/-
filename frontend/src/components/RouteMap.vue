<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AMapLoader from '@amap/amap-jsapi-loader'

const props=defineProps({days:{type:Array,default:()=>[]},transport:{type:String,default:'公共交通'},activeDay:{type:Number,default:0}})
const emit=defineEmits(['update:activeDay'])
const hasMapKey=Boolean(import.meta.env.VITE_AMAP_KEY)
const state=ref('准备绘制路线')
const colors=['#126b51','#e7773d','#3976a8','#8b62aa','#b68a26']
let map=null, AMap=null, renderVersion=0

function searchRoute(from,to){
  return new Promise(resolve=>{
    const km=pointDistance(from,to)
    const driving=/自驾|驾车/.test(props.transport)||km>1.8
    const planner=driving
      ? new AMap.Driving({hideMarkers:true,policy:AMap.DrivingPolicy.LEAST_TIME})
      : new AMap.Walking({hideMarkers:true})
    planner.search(new AMap.LngLat(...from),new AMap.LngLat(...to),(status,result)=>{
      if(status!=='complete'||!result.routes?.length)return resolve(null)
      const route=result.routes[0]
      const path=(route.steps||[]).flatMap(step=>step.path||[])
      resolve(path.length?path:null)
    })
  })
}
function pointDistance(from,to){
  const toRad=value=>value*Math.PI/180
  const lat=toRad(to[1]-from[1]),lon=toRad(to[0]-from[0])
  const q=Math.sin(lat/2)**2+Math.cos(toRad(from[1]))*Math.cos(toRad(to[1]))*Math.sin(lon/2)**2
  return 6371*2*Math.atan2(Math.sqrt(q),Math.sqrt(1-q))
}

async function render(){
  const version=++renderVersion
  if(!hasMapKey||!props.days.length)return
  state.value='正在调用高德规划真实道路…'
  if(!AMap){
    window._AMapSecurityConfig={securityJsCode:import.meta.env.VITE_AMAP_SECURITY_CODE||''}
    AMap=await AMapLoader.load({key:import.meta.env.VITE_AMAP_KEY,version:'2.0',plugins:['AMap.Walking','AMap.Driving']})
    map=new AMap.Map('route-map',{zoom:12,viewMode:'2D'})
  }
  map.clearMap()
  let markerNo=1,success=0,fallback=0
  const dayIndex=Math.min(props.activeDay,props.days.length-1)
  const day=props.days[dayIndex]
  {
    const stops=day.stops||[], attractionPoints=stops.map(s=>[s.longitude,s.latitude]), lodging=day.accommodation
    stops.forEach((s,i)=>new AMap.Marker({map,position:attractionPoints[i],title:s.name,
      label:{content:`D${dayIndex+1}-${i+1} ${s.name}`,direction:'top'},extData:{order:markerNo++}}))
    let points=attractionPoints
    if(lodging){
      const hotelPoint=[lodging.longitude,lodging.latitude]
      new AMap.Marker({map,position:hotelPoint,title:lodging.name,label:{content:`住宿 · ${lodging.name}`,direction:'bottom'},extData:{order:markerNo++}})
      points=[hotelPoint,...attractionPoints,hotelPoint]
      if(!lodging.sameAsPrevious&&dayIndex>0&&props.days[dayIndex-1]?.accommodation){
        const previous=props.days[dayIndex-1].accommodation,previousPoint=[previous.longitude,previous.latitude]
        const hotelPath=await searchRoute(previousPoint,hotelPoint)
        if(version!==renderVersion)return
        if(hotelPath){new AMap.Polyline({map,path:hotelPath,strokeColor:'#7b5b9b',strokeWeight:8,strokeOpacity:.8,showDir:true,zIndex:55});success++}
        else{new AMap.Polyline({map,path:[previousPoint,hotelPoint],strokeColor:'#7b5b9b',strokeWeight:3,strokeStyle:'dashed'});fallback++}
      }
    }
    for(let i=1;i<points.length;i++){
      const roadPath=await searchRoute(points[i-1],points[i])
      if(version!==renderVersion)return
      if(roadPath){
        new AMap.Polyline({map,path:roadPath,strokeColor:colors[dayIndex%colors.length],strokeWeight:7,strokeOpacity:.85,showDir:true,zIndex:50});success++
      }else{
        new AMap.Polyline({map,path:[points[i-1],points[i]],strokeColor:'#a8b1ad',strokeWeight:3,strokeStyle:'dashed',strokeOpacity:.7});fallback++
      }
    }
  }
  map.setFitView()
  state.value=`第 ${dayIndex+1} 天 · 已规划 ${success} 段真实道路${fallback?`，${fallback} 段暂以虚线降级`:''}`
}
function selectDay(index){emit('update:activeDay',index)}
onMounted(render)
watch(()=>[props.days,props.transport,props.activeDay],render,{deep:true})
onBeforeUnmount(()=>{renderVersion++;map?.destroy()})
</script>

<template>
  <div v-if="!hasMapKey" class="map-empty">配置前端高德 Key 后显示行程地图</div>
  <section v-else class="map-section"><div class="day-tabs"><button v-for="(day,index) in days" :key="day.day" :class="{active:props.activeDay===index}" @click="selectDay(index)"><b>第 {{day.day}} 天</b><small>{{day.date}} · {{day.theme}}</small></button></div><div class="map-wrap"><div id="route-map" class="route-map"></div><span class="route-state">{{state}}</span></div></section>
</template>

<style scoped>
.map-section{margin:20px 0}.day-tabs{display:flex;gap:9px;overflow-x:auto;padding:2px 2px 12px}.day-tabs button{min-width:150px;text-align:left;border:1px solid #d7ded8;border-radius:12px;background:white;color:#18342d;padding:10px 13px}.day-tabs button.active{background:#173d30;color:white;border-color:#173d30}.day-tabs b,.day-tabs small{display:block}.day-tabs small{margin-top:5px;opacity:.65;font-size:10px}.map-wrap{position:relative}.route-map,.map-empty{height:420px;border-radius:18px;background:#e8eee9}.map-empty{display:grid;place-items:center;color:#718079;font-size:13px}.route-state{position:absolute;left:14px;bottom:14px;background:#173d30e8;color:white;padding:8px 12px;border-radius:18px;font-size:11px;box-shadow:0 5px 18px #173d3040}:deep(.amap-marker-label){border:0;border-radius:14px;padding:5px 9px;box-shadow:0 4px 14px #173d3022;color:#173d30}
</style>
