import sys,json,urllib.request,urllib.parse
UA={"User-Agent":"SovereignComms/2.3 (data build; +https://kodiakking.com)"}
def get(u): return json.load(urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=180))
def fetch(base, out, fields="*", where="1=1", page=1000):
    feats=[]; off=0
    while True:
        q=urllib.parse.urlencode(dict(where=where,outFields=fields,outSR=4326,f="geojson",resultOffset=off,resultRecordCount=page,orderByFields="OBJECTID"))
        d=get(base+"/query?"+q); f=d["features"]; feats+=f; off+=len(f)
        if not f: break
    json.dump({"type":"FeatureCollection","features":feats},open(out,"w")); print(out,len(feats))
def stats(base, field):
    q=urllib.parse.urlencode(dict(where="1=1",groupByFieldsForStatistics=field,outStatistics=json.dumps([{"statisticType":"count","onStatisticField":"OBJECTID","outStatisticFieldName":"n"}]),f="json"))
    d=get(base+"/query?"+q); return sorted([(x["attributes"]["n"],x["attributes"][field]) for x in d["features"]],reverse=True)
if __name__=="__main__":
    D="https://arcgis.dnr.alaska.gov/arcgis/rest/services/OpenData/"
    for l in (0,1,2):
        print(l, stats(D+f"LandActivity_Easement/FeatureServer/{l}","CSTYPDSCRP")[:40])
    for f in ("STATUS","QUALIFY","WIDTH","CSSTTSDSCR","SPCLCDDSCR"):
        print(f, stats(D+"Infrastructure_RS2477Trails/FeatureServer/0",f)[:20])
