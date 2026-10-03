# Control for the no-reply check: run the same property unthrottled, with the status/height checks removed, so only
# the reply check can fail. Expected: it fails (the full path replies with a Sync).
p='src/test/scala/org/ergoplatform/network/ErgoNodeViewSynchronizerSpecification.scala'
s=open(p).read()
i=s.index('throttled V1 SyncInfo, or a V2'); j=s.index('NewBestInputBlock(local=true) broadcasts IBI',i)
b=s[i:j]
n=b.count('      throttleNext()\n')
b=b.replace('      throttleNext()\n','      if (false) throttleNext()\n')
b=b.replace('      syncTracker.getStatus(follower) shouldBe Some(Older)\n','')
b=b.replace('      syncTracker.statuses.get(follower).map(_.height) shouldBe Some(fullHeight - 5)\n','')
assert n==2 and 'repliesToFollower() shouldBe empty' in b
open(p,'w').write(s[:i]+b+s[j:])
