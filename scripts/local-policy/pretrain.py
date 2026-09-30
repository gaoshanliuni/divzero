"""Reproducible synthetic pretraining for the bounded Java action-value network.
These are admissible-action cost labels, not evidence of PvP win rate.
No external model, player data, world files or model-service calls are used.
"""
from pathlib import Path
import os,json,hashlib,argparse
os.environ.setdefault('OPENBLAS_NUM_THREADS','1')
import numpy as np
parser=argparse.ArgumentParser();parser.add_argument("--output",type=Path,default=Path(__file__).resolve().parents[2]/"build/policy-candidates/synthetic-baseline.json");args=parser.parse_args()
rng=np.random.default_rng(20260930)
size=24000
x=rng.uniform(0,1,(size,16));x[:,5]=rng.uniform(-1,1,size);x[:,8:10]=rng.uniform(-1,1,(size,2));x[:,3]=(x[:,3]>.8);x[:,11]=(x[:,11]>.5);x[:,13]=(x[:,13]>.7)
y=np.clip(.04+.48*x[:,6]*(1+.5*(1-x[:,0]))+.38*x[:,10]+.13*x[:,7]+.08*x[:,15]-.16*x[:,5]-.07*x[:,4]*x[:,11]+.04*x[:,13]-.05*x[:,0],.015,.985)
train=np.arange(20000);valid=np.arange(20000,size)
w=rng.normal(0,.18,(24,16));b=np.zeros(24);v=rng.normal(0,.12,24);c=np.array(0.)
params=[w,b,v,c];m=[np.zeros_like(p) for p in params];q=[np.zeros_like(p) for p in params]
for step in range(1,2401):
 batch=rng.choice(train,256,replace=False);features=x[batch];target=y[batch]
 h=np.tanh(features@w.T+b);z=np.clip(h@v+c,-30,30);pred=1/(1+np.exp(-z));g=(pred-target)/len(batch)
 dh=(g[:,None]*v)*(1-h*h);grads=[dh.T@features,dh.sum(0),h.T@g,g.sum()]
 for i,(p,d) in enumerate(zip(params,grads)):
  m[i]=.9*m[i]+.1*d;q[i]=.999*q[i]+.001*d*d;p-=.004*(m[i]/(1-.9**step))/(np.sqrt(q[i]/(1-.999**step))+1e-8)
pred=1/(1+np.exp(-(np.tanh(x[valid]@w.T+b)@v+c)))
loss=float(np.mean((pred-y[valid])**2));assert loss<.002,loss
root=args.output.parent;root.mkdir(parents=True,exist_ok=True)
model={'schema':'divzero-admissible-action-value/2','version':1,'hidden':w.tolist(),'bias':b.tolist(),'output':v.tolist(),'outputBias':float(c),'provenance':'SYNTHETIC_JAVA_COST_PRETRAINING_20260930'}
raw=(json.dumps(model,separators=(',',':'))+'\n').encode();args.output.write_bytes(raw)
manifest={'seed':20260930,'architecture':'16 inputs / 24 tanh hidden / 1 sigmoid cost','trainingSamples':len(train),'heldOutSamples':len(valid),'iterations':2400,'heldOutMSE':loss,'sha256':hashlib.sha256(raw).hexdigest(),'numpy':np.__version__,'source':'DivZero synthetic admissible-action cost envelopes; no external weights or player data','limits':'This validates approximation of cost labels only. Native combat and task outcome tests are separate.','features':['health','distance','speed','boost_active','attack_ready','goal_progress','forecast_risk','route_length','delta_x','delta_z','edge_risk','attack_opportunity','work_kind','airborne','weapon_damage','material_cost']}
args.output.with_suffix('.report.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(manifest))
