import json, urllib.request, urllib.parse
P="https://services1.arcgis.com/R5BNizttyFKxRSMm/arcgis/rest/services/KIB_Parcels/FeatureServer/0/query"
cl=json.load(open('dot_centerlines_kodiak.geojson'))
line=[]
for f in cl['features']: line+= [p[:2] for p in f['geometry']['coordinates']]
geom={"paths":[[p for p in f['geometry']['coordinates'] for _ in [0]][::5] for f in cl['features']],"spatialReference":{"wkid":4326}}
geom["paths"]=[[q[:2] for q in path] for path in geom["paths"]]
feats=[]; off=0
while True:
    data=urllib.parse.urlencode({"geometry":json.dumps(geom),"geometryType":"esriGeometryPolyline","inSR":4326,"spatialRel":"esriSpatialRelIntersects",
      "distance":120,"units":"esriSRUnit_Meter","where":"1=1","outFields":"Parcels_prop_id,PACS_Data_file_as_name,PACS_Data_Legal_Description,PACS_Data_Usage","outSR":4326,
      "resultOffset":off,"resultRecordCount":2000,"f":"geojson"}).encode()
    d=json.load(urllib.request.urlopen(urllib.request.Request(P,data=data),timeout=120))
    fs=d.get('features',[]); feats+=fs; print(off,len(fs),d.get('error'))
    if len(fs)<2000: break
    off+=2000
json.dump({"type":"FeatureCollection","features":feats},open('raw_kib_parcels_corridor.geojson','w'))
print('total',len(feats))
