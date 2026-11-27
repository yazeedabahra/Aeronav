package com.yvora.app
import org.junit.Assert.*
import org.junit.Test
class TutorialPracticeTest {
    private fun sample(t:Long,x:Float=.3f,pinch:Float=.5f,open:Boolean=true)=PracticeSample(t,x,.5f,pinch,open,.9f)
    @Test fun distinctFramesAndCompleteSequence(){
        val p=TutorialPractice()
        assertEquals(PracticeStage.OPEN,p.observe(sample(0),null,0))
        assertEquals(PracticeStage.OPEN,p.observe(sample(0),null,100))
        assertEquals(PracticeStage.MOVE,p.observe(sample(200),null,200))
        assertEquals(PracticeStage.PINCH,p.observe(sample(300,.5f),null,300))
        assertEquals(PracticeStage.RELEASE,p.observe(sample(400,.5f,.1f,false),null,400))
        assertEquals(PracticeStage.DONE,p.observe(sample(500,.5f),null,500))
    }
    @Test fun lossAndWrongHandCannotComplete(){
        val p=TutorialPractice()
        p.observe(sample(0),null,0);p.observe(sample(100),null,100)
        assertEquals(PracticeStage.OPEN,p.observe(null,sample(200,.8f),200))
        p.observe(sample(300),null,300);p.observe(sample(400),null,400)
        assertEquals(PracticeStage.OPEN,p.observe(sample(400),null,1000))
    }
}
